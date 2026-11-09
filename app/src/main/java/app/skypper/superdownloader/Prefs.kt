package app.skypper.superdownloader

import android.content.Context
import android.content.SharedPreferences

/** One History row: the shared link and the file that came out of it. */
data class HistoryEntry(
    val link: String,
    val uri: String,
    val thumb: String = "",
)

/**
 * Preferences: destination, quality and the backup of the folder choice.
 *
 * The backup exists because a SAF grant can be revoked (app uninstalled,
 * card removed, user clearing data of the source). If the grant dies, the app
 * asks for the folder again instead of silently downloading to the wrong place.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Selected preset: whatsapp, highest or custom. It is the value automatic
     * mode uses when it is on.
     */
    var qualityKey: String
        get() = sp.getString(KEY_QUALITY, Quality.DEFAULT_KEY) ?: Quality.DEFAULT_KEY
        set(value) = sp.edit().putString(KEY_QUALITY, value).apply()

    /** Height chosen inside Custom (e.g. "1080"). */
    var customHeightKey: String
        get() = sp.getString(KEY_CUSTOM_HEIGHT, "1080") ?: "1080"
        set(value) = sp.edit().putString(KEY_CUSTOM_HEIGHT, value).apply()

    /**
     * When on, the app downloads without asking, using the saved preset. When off,
     * the screen shows the selector to pick the quality before downloading.
     */
    var autoQuality: Boolean
        get() = sp.getBoolean(KEY_AUTO_QUALITY, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_QUALITY, value).apply()

    var quality: Quality
        get() = Quality.byKey(qualityKey)
        set(value) {
            qualityKey = value.key
        }

    /** What to use on a download: the preset, or on Custom the chosen height. */
    fun effectiveQuality(): Quality {
        val preset = quality
        return if (preset.preset == QualityPreset.CUSTOM) {
            Quality(customHeightKey, Quality.byKey(customHeightKey).label)
        } else preset
    }

    /**
     * Quality used for downloads that arrive via SHARE.
     *
     * People usually share a video one way and paste a link another, so
     * the preset chosen in QUALITY applies to the link pasted on screen, and this one
     * applies only when the app is opened from the system share sheet.
     * Empty = uses the normal preset.
     */
    var shareQualityKey: String
        get() = sp.getString(KEY_SHARE_QUALITY, "").orEmpty()
        set(value) = sp.edit().putString(KEY_SHARE_QUALITY, value).apply()

    /** Turns a dedicated quality for share on/off. */
    var shareQualityEnabled: Boolean
        get() = sp.getBoolean(KEY_SHARE_QUALITY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_SHARE_QUALITY_ENABLED, value).apply()

    /** Effective quality for this download, taking the origin into account. */
    fun qualityFor(fromShare: Boolean): Quality =
        if (fromShare && shareQualityEnabled && shareQualityKey.isNotBlank()) {
            Quality.byKey(shareQualityKey)
        } else {
            effectiveQuality()
        }

    /** Last valid folder chosen by the user. */
    var destTree: String
        get() = sp.getString(KEY_TREE, "").orEmpty()
        set(value) = sp.edit().putString(KEY_TREE, value).apply()

    var destLabel: String
        get() = sp.getString(KEY_LABEL, "").orEmpty()
        set(value) = sp.edit().putString(KEY_LABEL, value).apply()

    fun saveDest(tree: String, label: String) {
        sp.edit().putString(KEY_TREE, tree).putString(KEY_LABEL, label).apply()
    }

    /**
     * The latest downloads (up to 10): the link AND the file.
     *
     * The link is for downloading again; the file is what the share
     * button hands over. Storing only the link would force resolving and downloading
     * again just to share what is already on the device.
     *
     * Serialized as `link|uri` per line — simple, no JSON, and an item without
     * a uri (old download) stays valid.
     */
    var history: List<HistoryEntry>
        get() = sp.getString(KEY_HISTORY, "").orEmpty()
            .split("\n")
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split("|")
                HistoryEntry(
                    link = parts.getOrNull(0).orEmpty(),
                    uri = parts.getOrNull(1).orEmpty(),
                    thumb = parts.getOrNull(2).orEmpty()
                )
            }
        set(value) {
            val clean = value
                .filter { it.link.isNotBlank() }
                .distinctBy { it.link }
                .take(HISTORY_LIMIT)
            sp.edit()
                .putString(KEY_HISTORY, clean.joinToString("\n") { "${it.link}|${it.uri}|${it.thumb}" })
                .apply()
        }

    fun pushHistory(link: String, uri: String, thumb: String = "") {
        if (link.isBlank()) return
        history = listOf(HistoryEntry(link, uri, thumb)) + history.filter { it.link != link }
    }

    companion object {
        private const val FILE = "super_downloader"
        private const val KEY_QUALITY = "quality"
        private const val KEY_CUSTOM_HEIGHT = "custom_height"
        private const val KEY_AUTO_QUALITY = "auto_quality"
        private const val KEY_HISTORY = "history"
        private const val KEY_SHARE_QUALITY = "share_quality"
        private const val KEY_SHARE_QUALITY_ENABLED = "share_quality_enabled"
        private const val HISTORY_LIMIT = 10
        private const val KEY_TREE = "dest_tree"
        private const val KEY_LABEL = "dest_label"
    }
}
