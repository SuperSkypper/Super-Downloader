package app.skypper.superdownloader

import java.util.Locale

/** Where the file will land, chosen by the user. Empty = app default. */
data class Dest(val tree: String, val label: String) {
    val isSet: Boolean get() = tree.isNotBlank()
    companion object {
        val NONE = Dest("", "")
    }
}

/**
 * What the user picks in the quality selector.
 *
 * Three presets plus Custom:
 *  - HIGHEST: the highest resolution the post has, without re-encoding;
 *  - WHATSAPP: up to 720p and, if over 62 MB, re-encodes to fit the send limit;
 *  - CUSTOM: a specific height (4K, 1440p, 1080p ...), bounded by what the
 *    post offers — never "4K" on a 360p video.
 */
enum class QualityPreset(val key: String, val label: String) {
    WHATSAPP("whatsapp", "WhatsApp"),
    AUDIO("audio", "Audio Only"),
    HIGHEST("highest", "Highest Quality"),
    CUSTOM("custom", "Custom");

    companion object {
        val DEFAULT = WHATSAPP

        fun byKey(key: String?): QualityPreset =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** One height on the Custom ladder, already carrying the label the user sees. */
data class Quality(val key: String, val label: String) {

    /** "Audio Only": downloads just the audio track, extracted from the post's MP4. */
    val isAudioOnly: Boolean get() = key == QualityPreset.AUDIO.key

    val isWhatsApp: Boolean get() = key == QualityPreset.WHATSAPP.key
    val isHighest: Boolean get() = key == QualityPreset.HIGHEST.key

    val preset: QualityPreset
        get() = when (key) {
            QualityPreset.AUDIO.key -> QualityPreset.AUDIO
            QualityPreset.HIGHEST.key -> QualityPreset.HIGHEST
            QualityPreset.CUSTOM.key -> QualityPreset.CUSTOM
            else -> QualityPreset.WHATSAPP
        }

    /** Target height; null = the largest available. */
    val height: Int?
        get() = when {
            isWhatsApp -> 720
            isHighest -> null
            key == QualityPreset.CUSTOM.key -> null
            else -> key.toIntOrNull()
        }

    companion object {
        const val KEY_WHATSAPP = "whatsapp"
        const val KEY_HIGHEST = "highest"
        const val KEY_CUSTOM = "custom"

        /** The ladder Custom offers, best to worst. */
        val LADDER = listOf(
            Quality("2160", "4K"),
            Quality("1440", "1440p"),
            Quality("1080", "1080p"),
            Quality("720", "720p"),
            Quality("480", "480p"),
            Quality("360", "360p"),
            Quality("240", "240p"),
            Quality("144", "144p")
        )

        const val KEY_AUDIO = "audio"

        val ALL = listOf(
            Quality(KEY_WHATSAPP, "WhatsApp"),
            Quality(KEY_AUDIO, "Audio Only"),
            Quality(KEY_HIGHEST, "Highest Quality"),
            Quality(KEY_CUSTOM, "Custom")
        )

        const val DEFAULT_KEY = KEY_WHATSAPP

        fun byKey(key: String?): Quality =
            ALL.firstOrNull { it.key == key }
                ?: LADDER.firstOrNull { it.key == key }
                ?: Quality(KEY_WHATSAPP, "WhatsApp")

        /**
         * Which heights on the ladder the post actually offers.
         *
         * "No point having 4K if the video is 360p": the ladder is cut at the top
         * the post itself publishes. With no information (post not read yet),
         * everything shows as enabled.
         */
        fun ladderFor(maxAvailable: Int?): List<Quality> {
            if (maxAvailable == null || maxAvailable <= 0) return LADDER
            val shown = LADDER.filter { it.height!! <= maxAvailable }
            // The post always has something; if no rung fits, show the smallest.
            return shown.ifEmpty { listOf(LADDER.last()) }
        }
    }
}

/** One encoding variant that the vxtwitter API publishes for the video. */
data class Variant(val url: String, val height: Int, val kbps: Int)

/** A video from the post, with the variants X offers (may be just the final MP4). */
data class MediaItem(
    val variants: List<Variant>,
    val screenName: String,
    val mediaId: String,
    val durationMs: Long
) {
    /** File name: the media id, never the tweet id. */
    fun baseName(): String {
        val who = screenName.lowercase().replace(Regex("[^a-z0-9._-]"), "").ifBlank { "video" }
        return if (mediaId.isNotBlank()) "${who}_${mediaId}" else who
    }

    fun durationLabel(): String {
        if (durationMs <= 0) return ""
        val total = durationMs / 1000
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }
}

class ResolveException(message: String) : Exception(message)
