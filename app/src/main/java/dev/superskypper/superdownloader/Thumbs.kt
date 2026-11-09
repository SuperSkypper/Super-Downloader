package dev.superskypper.superdownloader

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Primeiro frame do video, recortado em quadrado, para o History mostrar de qual
 * video se trata.
 *
 * O frame vai para `filesDir/thumbs` como JPEG de 128px: guardar o Bitmap em
 * memoria morreria junto com o processo, e reler o video a cada render custaria
 * caro. O nome e o hash da URI, entao a mesma linha reaproveita a imagem.
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
            // Video sem frame decodificavel: o History fica sem miniatura, nao sem linha.
            ""
        } finally {
            runCatching { retriever.release() }
        }
    }
}
