package app.skypper.superdownloader

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * First frame of the video, cropped to a square, so History can show which
 * video it is.
 *
 * The frame goes to `filesDir/thumbs` as a 128px JPEG: keeping the Bitmap in
 * memory would die with the process, and re-reading the video on every render would be
 * expensive. The name is the hash of the URI, so the same row reuses the image.
 */
object Thumbs {

    private const val SIDE = 160

    fun make(context: Context, uri: Uri): String {
        if (uri.toString().isBlank()) return ""
        val dir = File(context.filesDir, "thumbs").apply { mkdirs() }
        val file = File(dir, "${uri.toString().hashCode().toUInt().toString(16)}.jpg")
        if (file.exists() && file.length() > 0) return file.absolutePath

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
                ?: return ""
            val side = minOf(frame.width, frame.height)
            if (side <= 0) return ""
            val square = Bitmap.createBitmap(
                frame,
                (frame.width - side) / 2,
                (frame.height - side) / 2,
                side,
                side
            )
            val scaled = Bitmap.createScaledBitmap(square, SIDE, SIDE, true)
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            if (scaled !== square) scaled.recycle()
            if (square !== frame) square.recycle()
            file.absolutePath
        } catch (t: Throwable) {
            // Video with no decodable frame: History ends up without a thumbnail, not without a row.
            ""
        } finally {
            runCatching { retriever.release() }
        }
    }
}
