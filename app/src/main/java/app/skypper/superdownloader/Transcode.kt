package app.skypper.superdownloader

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.Statistics
import java.util.concurrent.CountDownLatch
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/**
 * WhatsApp preset re-encode.
 *
 * X publishes at most ~1.9 Mbps at 720p, and 85 s of that is ~20 MB. The limit
 * for a video sent as media on WhatsApp is not 16 MB: the official FAQ says
 * 64 MB on a slow connection and 100 MB on a fast one, with a 720p cap. Using the
 * wrong number made the app recompress video that fit easily. The agreed rule: first try to fit while keeping
 * 720p, lowering the bitrate; drop resolution only when not even a floor bitrate
 * would fit. Two or three passes at most, measuring the real file between
 * them (the encoder does not deliver exactly the requested bitrate).
 */
object Transcode {

    // 62 MB: WhatsApp's 64 MB on a slow connection, with margin for the
    // container and for encoder rounding.
    const val WHATSAPP_LIMIT = 62_000_000L

    private const val MIN_KBPS = 250
    // 96k: 128k pushed the file to 64.15 MB, just above WhatsApp's real
    // ceiling (64 MB), and forced a whole second pass.
    private const val AUDIO_KBPS = 96

    data class Result(val file: File, val width: Int, val height: Int, val bytes: Long)

    fun shouldCompress(bytes: Long): Boolean = bytes > WHATSAPP_LIMIT

    /**
     * @param source downloaded file
     * @param durationSeconds real video duration
     * @param sourceShort short side of the source (to avoid upscaling)
     */
    fun fitForWhatsApp(
        context: Context,
        source: File,
        durationSeconds: Long,
        onProgress: (Float) -> Unit
    ): Result {
        val dir = File(context.cacheDir, "wa")
        dir.mkdirs()
        // Leftovers from previous attempts took hundreds of MB in the cache.
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val duration = if (durationSeconds > 0) durationSeconds else 1

        // Total file budget: audio comes off first, the rest is video.
        fun budgetKbps(): Int =
            (WHATSAPP_LIMIT * 8L / duration / 1000L).toInt() - AUDIO_KBPS

        // If the budget is already below the floor, try ONCE at the floor: the
        // file comes out far smaller than the original, which is the best possible for a
        // video that is too long, instead of refusing and leaving the 300 MB intact.
        // Ceiling on the bitrate of the ORIGINAL itself: asking for more than that inflates
        // the file (a 1.7 Mbps video became 59 MB when asking for 5707k).
        val sourceKbps = (source.length() * 8L / duration / 1000L).toInt()
        var videoKbps = budgetKbps().coerceAtLeast(MIN_KBPS)
        if (sourceKbps > 0) videoKbps = videoKbps.coerceAtMost(sourceKbps)

        var best: Result? = null
        var hardware = true

        // RESOLUTION DOES NOT CHANGE: the video keeps its original size and only the bitrate
        // drops. Reducing to 480p/360p ruined the image with no guarantee of fitting,
        // because file size depends on the bitrate, not on the pixel count.
        // Each attempt measures the real result and corrects the target proportionally,
        // since x264 does not deliver the requested bitrate precisely.
        for (attempt in 0 until 3) {
            if (videoKbps < MIN_KBPS) break

            val target = File(dir, "fit_$attempt.mp4")
            if (target.exists()) target.delete()

            // Hardware first (MediaCodec), software as a safety net.
            var ok = false
            if (hardware) {
                ok = runCatching {
                    val silent = File(dir, "hw_$attempt.mp4")
                    // MediaCodec in CBR delivers ~18% MORE bitrate than requested
                    // (948k requested became 1122k actual): without compensating, the first
                    // pass blew past the limit and the app restarted the whole
                    // encode — the "Compressing in loop" the user saw.
                    HardwareTranscode.videoOnly(source, silent, videoKbps * 84 / 100) {
                        onProgress(it * 0.92f)
                    }
                    val remuxed = remuxWithAudio(silent, source, target)
                    runCatching { silent.delete() }
                    if (!remuxed) throw IllegalStateException("remux failed")
                }.isSuccess
                if (!ok) {
                    // Device with no compatible H.264 encoder: the rest goes through x264.
                    hardware = false
                    runCatching { target.delete() }
                }
            }

            if (!ok) {
                val command = buildString {
                    append("-y -i ")
                    append(quote(source.absolutePath))
                    // `ultrafast` in software: ~2-3x faster than `veryfast`,
                    // which was already necessary (default preset runs at 0.2x).
                    append(" -c:v libx264 -preset ultrafast -profile:v high -level 4.0")
                    append(" -b:v ${videoKbps}k -maxrate ${videoKbps * 11 / 10}k -bufsize ${videoKbps}k")
                    append(" -pix_fmt yuv420p")
                    append(" -c:a aac -b:a ${AUDIO_KBPS}k -ar 44100 -ac 2")
                    append(" -movflags +faststart ")
                    append(quote(target.absolutePath))
                }
                val code = exec(command, duration * 1000) { onProgress(it) }
                if (!ReturnCode.isSuccess(code) || target.length() <= 0) {
                    runCatching { target.delete() }
                    throw IllegalStateException("the re-encode failed")
                }
            }

            val size = target.length()

            // ONE pass is the result. This device's hardware encoder
            // delivers ~1 Mbps even when 800k is requested, so a second pass
            // does not get closer to the target — it just redoes minutes of encoding and showed up
            // as "Compressing" in a loop. Accept any result smaller than the
            // original; the final size goes on screen.
            if (size in 1 until source.length()) {
                best?.file?.let { runCatching { it.delete() } }
                return Result(target, 0, 0, size)
            }

            // Only continue when the encoder returned something useless (larger than the
            // source): then it is worth trying again with another bitrate.
            if (best == null || size < best!!.bytes) {
                best?.file?.let { runCatching { it.delete() } }
                best = Result(target, 0, 0, size)
            } else {
                target.delete()
            }
            videoKbps = videoKbps * 8 / 10
        }

        // No attempt fit: return the smallest one. Smaller than the original is still
        // better than the original, and the screen reports the final size.
        best?.let { return it }
        throw IllegalStateException("could not compress the video to fit WhatsApp")
    }

    /**
     * Extracts only the audio track of the downloaded video, as M4A (AAC).
     *
     * Downloading the whole MP4 and discarding the video is simpler and more reliable
     * than assembling the audio from HLS: X publishes the audio track separately, but
     * joining the fragments on the device has already failed on posts with odd durations.
     */
    fun extractAudio(context: Context, source: File, onProgress: (Float) -> Unit): Result {
        val durationMs = runCatching {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(source.absolutePath)
                r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            } finally {
                runCatching { r.release() }
            }
        }.getOrDefault(0L)
        val dir = File(context.cacheDir, "audio").apply { mkdirs() }
        val target = File(dir, "audio.m4a")
        if (target.exists()) target.delete()

        val command = buildString {
            append("-y -i ")
            append(quote(source.absolutePath))
            append(" -vn -c:a copy -movflags +faststart ")
            append(quote(target.absolutePath))
        }
        val code = exec(command, durationMs) { onProgress(it) }
        if (!ReturnCode.isSuccess(code)) {
            // The track may use a codec that cannot be copied into m4a; re-encode it.
            val fallback = buildString {
                append("-y -i ")
                append(quote(source.absolutePath))
                append(" -vn -c:a aac -b:a 192k -ar 44100 -ac 2 ")
                append(quote(target.absolutePath))
            }
            val secondCode = exec(fallback, durationMs) { onProgress(it) }
            if (!ReturnCode.isSuccess(secondCode)) {
                runCatching { target.delete() }
                throw IllegalStateException("the audio extraction failed")
            }
        }
        onProgress(1f)
        val size = target.length()
        if (size <= 0) throw IllegalStateException("the audio extraction produced an empty file")
        return Result(target, 0, 0, size)
    }

    /**
     * Joins the hardware-transcoded video with the ORIGINAL audio of the file,
     * without re-encoding (`-c copy`): takes less than a second and preserves the audio.
     * The `?` in `1:a:0?` makes the map optional, for videos with no audio track.
     */
    private fun remuxWithAudio(video: File, source: File, target: File): Boolean {
        val command = "-y -i ${quote(video.absolutePath)} -i ${quote(source.absolutePath)}" +
            " -map 0:v:0 -map 1:a:0? -c copy -movflags +faststart ${quote(target.absolutePath)}"
        val code = exec(command, 0) { }
        return ReturnCode.isSuccess(code) && target.length() > 0
    }

    /**
     * Runs ffmpeg reporting progress from the position of the processed timestamp.
     *
     * `FFmpegKit.execute` is synchronous and calls no callback at all, so the bar
     * sat still for the whole compression — the user had no way to know
     * the app was working. `executeAsync` with StatisticsCallback
     * delivers `time` in ms, which divided by the duration gives the percentage.
     */
    private fun exec(
        command: String,
        durationMs: Long,
        onProgress: (Float) -> Unit
    ): com.arthenica.ffmpegkit.ReturnCode? {
        val latch = CountDownLatch(1)
        var code: com.arthenica.ffmpegkit.ReturnCode? = null
        FFmpegKit.executeAsync(
            command,
            { session ->
                code = session.returnCode
                latch.countDown()
            },
            null,
            { statistics: Statistics ->
                if (durationMs > 0) {
                    onProgress((statistics.time / durationMs.toDouble()).toFloat().coerceIn(0f, 1f))
                }
            }
        )
        latch.await()
        return code
    }

    private fun quote(path: String): String = "\"" + path.replace("\"", "\\\"") + "\""
}
