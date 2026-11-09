package app.skypper.superdownloader

import java.util.Locale

/** Onde o arquivo vai cair, escolhido pelo usuario. Vazio = default do app. */
data class Dest(val tree: String, val label: String) {
    val isSet: Boolean get() = tree.isNotBlank()
    companion object {
        val NONE = Dest("", "")
    }
}

/**
 * O que o usuario escolhe no seletor de qualidade.
 *
 * Tres presets e o Custom:
 *  - HIGHEST: a maior resolucao que o post tiver, sem recomprimir;
 *  - WHATSAPP: ate 720p e, se passar de 62 MB, recomprime para caber no envio;
 *  - CUSTOM: uma altura especifica (4K, 1440p, 1080p ...), limitada ao que o
 *    post oferece — nunca "4K" num video de 360p.
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

/** Uma altura da escada do Custom, ja com o rotulo que o usuario le. */
data class Quality(val key: String, val label: String) {

    /** "Audio Only": baixa so a faixa de audio, extraida do MP4 do post. */
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

    /** Altura alvo; null = a maior disponivel. */
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

        /** A escada que o Custom oferece, da melhor para a pior. */
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
         * Quais alturas da escada o post realmente oferece.
         *
         * "Nao adianta ter 4K se o video e 360p": a escada e cortada no topo que
         * o proprio post publica. Sem informacao (post ainda nao lido), tudo
         * aparece habilitado.
         */
        fun ladderFor(maxAvailable: Int?): List<Quality> {
            if (maxAvailable == null || maxAvailable <= 0) return LADDER
            val shown = LADDER.filter { it.height!! <= maxAvailable }
            // O post sempre tem alguma coisa; se nenhum degrau couber, mostra o menor.
            return shown.ifEmpty { listOf(LADDER.last()) }
        }
    }
}

/** Uma variante de encoding que a API do vxtwitter publica para o video. */
data class Variant(val url: String, val height: Int, val kbps: Int)

/** Um video do post, com as variantes que o X oferece (pode ser so o MP4 final). */
data class MediaItem(
    val variants: List<Variant>,
    val screenName: String,
    val mediaId: String,
    val durationMs: Long
) {
    /** Nome do arquivo: o media id, nunca o id do tweet. */
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
