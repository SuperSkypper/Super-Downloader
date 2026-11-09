package app.skypper.superdownloader

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Compression using the device's hardware encoder (MediaCodec).
 *
 * Software x264 runs at ~0.9x on this phone: a 7min40 video took over
 * eight minutes and looked frozen. This project's ffmpeg-kit does NOT ship
 * `h264_mediacodec` as an encoder (only decoders), so the GPU was not reachable through
 * ffmpeg — the path is to talk MediaCodec directly, with the decoder writing to the
 * encoder's surface.
 *
 * Only the VIDEO is transcoded here. The audio is copied with `-c copy` in ffmpeg afterwards,
 * which takes less than a second: mixing both tracks in the same MediaMuxer
 * would require interleaving the samples by hand, with a risk of a corrupted muxer.
 */
object HardwareTranscode {

    class Failure(message: String) : Exception(message)

    /**
     * @return size of the generated file (no audio).
     * @throws Failure when the device has no compatible H.264 encoder.
     */
    fun videoOnly(
        source: File,
        target: File,
        videoKbps: Int,
        onProgress: (Float) -> Unit
    ): Long {
        if (target.exists()) target.delete()

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var surface: android.view.Surface? = null

        try {
            extractor.setDataSource(source.absolutePath)

            var videoTrack = -1
            var srcFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    videoTrack = i
                    srcFormat = f
                    break
                }
            }
            val format = srcFormat ?: throw Failure("no video track")
            extractor.selectTrack(videoTrack)

            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)

            // EVEN dimensions: the H.264 encoder rejects odd ones (1080x1921, e.g.).
            val outWidth = width and 1.inv()
            val outHeight = height and 1.inv()

            val encFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, outWidth, outHeight
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, videoKbps * 1000)
                // This device's encoder alone delivers ~1 Mbps even when asking
                // for 800k; `KEY_MAX_BIT_RATE` is what Samsung hardware encoders
                // actually respect.
                setInteger("max-bitrate", videoKbps * 1000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // CBR: the size target needs predictability.
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                )
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
                it.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            surface = encoder.createInputSurface()
            encoder.start()

            decoder = MediaCodec.createDecoderByType(
                format.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC
            ).also {
                it.configure(format, surface, null, 0)
                it.start()
            }

            muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            pump(extractor, decoder, encoder, muxer, durationUs, onProgress)
            return target.length()
        } catch (t: Throwable) {
            runCatching { target.delete() }
            throw if (t is Failure) t else Failure(t.message ?: "media codec failed")
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { surface?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun pump(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        encoder: MediaCodec,
        muxer: MediaMuxer,
        durationUs: Long,
        onProgress: (Float) -> Unit
    ) {
        val info = MediaCodec.BufferInfo()
        var muxTrack = -1
        var muxStarted = false
        var inputDone = false
        var outputDone = false
        var lastReport = 0f

        while (!outputDone) {
            // --- feed the decoder with the file's samples ---
            if (!inputDone) {
                val index = decoder.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val buffer = decoder.getInputBuffer(index) ?: ByteBuffer.allocate(0)
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(
                            index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            // --- decoder -> encoder surface ---
            var decIndex = decoder.dequeueOutputBuffer(info, 0)
            while (decIndex >= 0) {
                // `render = true` delivers the frame to the surface; without it the encoder
                // never receives an image.
                decoder.releaseOutputBuffer(decIndex, true)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    encoder.signalEndOfInputStream()
                }
                decIndex = decoder.dequeueOutputBuffer(info, 0)
            }

            // --- drain the encoder into the muxer ---
            var encIndex = encoder.dequeueOutputBuffer(info, 10_000)
            while (encIndex >= 0 || encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxStarted) {
                        muxTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxStarted = true
                    }
                } else {
                    val outBuffer = encoder.getOutputBuffer(encIndex)
                    if (outBuffer != null && info.size > 0 && muxStarted) {
                        // Config (SPS/PPS) already goes in the track format: do not write it.
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            outBuffer.position(info.offset)
                            outBuffer.limit(info.offset + info.size)
                            muxer.writeSampleData(muxTrack, outBuffer, info)
                        }
                    }
                    encoder.releaseOutputBuffer(encIndex, false)

                    val fraction = (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                    if (fraction - lastReport > 0.01f || fraction >= 1f) {
                        lastReport = fraction
                        onProgress(fraction)
                    }

                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                        break
                    }
                }
                encIndex = encoder.dequeueOutputBuffer(info, 0)
            }
        }
        onProgress(1f)
    }
}
