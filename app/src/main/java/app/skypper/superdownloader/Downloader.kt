package app.skypper.superdownloader

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class CancelledException : Exception("cancelado")

/**
 * Downloads the video and returns the content URI that the share sheet can read.
 *
 * Two possible destinations:
 *  - the folder chosen by the user (SAF tree) -> writes there, which may be
 *    WhatsApp's own Download/Agent folder, the SD card or Google Drive;
 *  - nothing chosen -> MediaStore.Downloads in Download/Agent, with no permission.
 *
 * In the WhatsApp preset the downloaded file is the largest variant up to 720 and the app
 * guarantees the rest of the format (H.264 + AAC, faststart): if the MP4 from X already comes
 * that way, nothing is re-encoded on the device.
 */
object Downloader {

    const val FOLDER = "Download/Super Downloader"
    const val DEFAULT_LABEL = "Download/Super Downloader"
    const val MIME = "video/mp4"
    const val MIME_AUDIO = "audio/mp4"

    /** Real duration and short side of the video, read from the file itself. */
    data class Probe(val durationMs: Long, val shortSide: Int)

    /**
     * Measures the file instead of trusting the post model: `variant.height` and
     * `variant.durationMs` came swapped/empty and the compression bitrate was
     * computed on top of a 1-second duration (it asked for 63 Mbps).
     */
    fun probe(context: Context, uri: Uri): Probe? = runCatching {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val ms = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            val w = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0
            val short = listOf(w, h).filter { it > 0 }.minOrNull() ?: 0
            Probe(ms, short)
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()

    data class Target(val uri: Uri, val name: String, val bytes: Long)

    fun fileName(item: MediaItem, quality: Quality, taken: MutableSet<String>): String {
        val base = item.baseName()
        val suffix = when {
            quality.isAudioOnly -> "_audio"
            quality.isWhatsApp -> "_whatsapp"
            quality.height != null -> "_${quality.height}p"
            else -> ""
        }
        val ext = if (quality.isAudioOnly) "m4a" else "mp4"
        var name = "$base$suffix.$ext"
        var n = 2
        while (!taken.add(name)) {
            name = "${base}${suffix}_$n.$ext"
            n++
        }
        return name
    }

    /**
     * Picks the variant by the requested height. "Best available" (height null)
     * takes the largest one in the post.
     */
    fun pick(item: MediaItem, quality: Quality): Variant? {
        val target = if (quality.isWhatsApp) quality.height else quality.height
        if (target == null) return item.variants.maxByOrNull { it.height } ?: item.variants.firstOrNull()
        val capped = item.variants.filter { it.height in 1..target }
        return capped.maxByOrNull { it.height }
            ?: item.variants.minByOrNull { it.height }
            ?: item.variants.firstOrNull()
    }

    fun download(
        context: Context,
        variant: Variant,
        displayName: String,
        mime: String = MIME,
        dest: Dest,
        onProgress: (bytes: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean
    ): Target {
        val resolver = context.contentResolver
        var uri = createTarget(resolver, dest, displayName, mime)

        var conn: HttpURLConnection? = null
        var written = 0L
        try {
            conn = (URL(variant.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("the server answered $code")
            val total = conn.contentLengthLong

            val out = resolver.openOutputStream(uri, "w") ?: throw IOException("could not open the file")
            out.use { o ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw CancelledException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        o.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                }
            }
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            runCatching { resolver.delete(uri, null, null) }
            throw t
        } finally {
            conn?.disconnect()
        }

        if (written == 0L) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw IOException("the server returned an empty file")
        }

        if (!dest.isSet) {
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
        return Target(uri, displayName, written)
    }

    /**
     * With SAF the write is direct. With MediaStore the file is created pending and only
     * shows up in the gallery after the download completes.
     */
    private fun createTarget(
        resolver: ContentResolver,
        dest: Dest,
        displayName: String,
        mime: String = MIME
    ): Uri {
        if (dest.isSet) {
            val tree = Uri.parse(dest.tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree)
            )
            removeSafExisting(resolver, tree, parent, displayName)
            return DocumentsContract.createDocument(resolver, parent, mime, displayName)
                ?: throw IOException("could not create the file in the selected folder")
        }

        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        removeMediaStoreExisting(resolver, collection, displayName)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(collection, values)
            ?: throw IOException("could not create the file in $FOLDER")
    }

    private fun removeSafExisting(
        resolver: ContentResolver,
        tree: Uri,
        parent: Uri,
        displayName: String
    ) {
        runCatching {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree,
                DocumentsContract.getDocumentId(parent)
            )
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val child = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                        val name = runCatching {
                            resolver.query(child, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                        }.getOrNull()
                        if (name == displayName) {
                            DocumentsContract.deleteDocument(resolver, child)
                        }
                    }
                }
        }
    }

    private fun removeMediaStoreExisting(
        resolver: ContentResolver,
        collection: Uri,
        displayName: String
    ) {
        runCatching {
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                "(${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?)"
            val args = arrayOf(displayName, "$FOLDER%", "$FOLDER/%")
            resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)
                ?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        resolver.delete(ContentUris.withAppendedId(collection, id), null, null)
                    }
                }
        }
    }

    /** Copies the content URI contents to the cache, which is where FFmpeg reads from. */
    fun localCopy(context: Context, uri: Uri): java.io.File {
        val dir = java.io.File(context.cacheDir, "src")
        dir.mkdirs()
        val out = java.io.File(dir, "source.mp4")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "could not open the downloaded file" }
            out.outputStream().use { o -> input.copyTo(o, 64 * 1024) }
        }
        return out
    }

    /**
     * Replaces the heavy file with the compressed one, keeping the visible name.
     *
     * In MediaStore the old entry is deleted and the new one inserted in
     * Download/Agent; with SAF the compressed file overwrites the chosen document.
     */
    fun replaceWith(
        context: Context,
        target: Target,
        compressed: java.io.File,
        dest: Dest,
        mime: String = MIME
    ): Uri {
        val resolver = context.contentResolver
        val name = target.name

        if (dest.isSet) {
            val tree = Uri.parse(dest.tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            runCatching { DocumentsContract.deleteDocument(resolver, target.uri) }
            val fresh = DocumentsContract.createDocument(resolver, parent, mime, name)
                ?: throw IOException("could not recreate the compressed file")
            resolver.openOutputStream(fresh, "w")?.use { o ->
                compressed.inputStream().use { input -> input.copyTo(o, 64 * 1024) }
            } ?: throw IOException("could not write the compressed file")
            runCatching { compressed.delete() }
            return fresh
        }

        runCatching { resolver.delete(target.uri, null, null) }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("could not recreate the compressed file")
        resolver.openOutputStream(uri, "w")?.use { o ->
            compressed.inputStream().use { input -> input.copyTo(o, 64 * 1024) }
        } ?: throw IOException("could not write the compressed file")
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        runCatching { compressed.delete() }
        return uri
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; SM_M315F) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"
}
