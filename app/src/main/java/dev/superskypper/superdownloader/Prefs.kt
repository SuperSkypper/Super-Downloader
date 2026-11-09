package dev.superskypper.superdownloader

import android.content.Context
import android.content.SharedPreferences

/** Uma linha do History: o link compartilhado e o arquivo que saiu dele. */
data class HistoryEntry(
    val link: String,
    val uri: String,
    val thumb: String = "",
)

/**
 * Preferencias: destino, qualidade e o backup da escolha de pasta.
 *
 * O backup existe porque um SAF grant pode ser revogado (app desinstalado,
 * cartao removido, usuario limpando dados da fonte). Se o grant morrer, o app
 * pede a pasta de novo em vez de baixar em silencio para o lugar errado.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Preset selecionado: whatsapp, highest ou custom. E o valor que o modo
     * automatico usa quando ele esta ligado.
     */
    var qualityKey: String
        get() = sp.getString(KEY_QUALITY, Quality.DEFAULT_KEY) ?: Quality.DEFAULT_KEY
        set(value) = sp.edit().putString(KEY_QUALITY, value).apply()

    /** Altura escolhida dentro do Custom (ex.: "1080"). */
    var customHeightKey: String
        get() = sp.getString(KEY_CUSTOM_HEIGHT, "1080") ?: "1080"
        set(value) = sp.edit().putString(KEY_CUSTOM_HEIGHT, value).apply()

    /**
     * Quando ligado, o app baixa sem perguntar, usando o preset salvo. Desligado,
     * a tela mostra o seletor para escolher a qualidade antes de baixar.
     */
    var autoQuality: Boolean
        get() = sp.getBoolean(KEY_AUTO_QUALITY, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_QUALITY, value).apply()

    var quality: Quality
        get() = Quality.byKey(qualityKey)
        set(value) {
            qualityKey = value.key
        }

    /** O que usar num download: o preset, ou no Custom a altura escolhida. */
    fun effectiveQuality(): Quality {
        val preset = quality
        return if (preset.preset == QualityPreset.CUSTOM) {
            Quality(customHeightKey, Quality.byKey(customHeightKey).label)
        } else preset
    }

    /**
     * Qualidade usada nos downloads que chegam por SHARE.
     *
     * A pessoa costuma compartilhar video de um jeito e colar link de outro, entao
     * o preset escolhido em QUALITY vale para o link colado na tela, e este aqui
     * so vale quando o app e aberto pela folha de compartilhamento do sistema.
     * Vazio = usa o preset normal.
     */
    var shareQualityKey: String
        get() = sp.getString(KEY_SHARE_QUALITY, "").orEmpty()
        set(value) = sp.edit().putString(KEY_SHARE_QUALITY, value).apply()

    /** Liga/desliga uma qualidade propria para share. */
    var shareQualityEnabled: Boolean
        get() = sp.getBoolean(KEY_SHARE_QUALITY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_SHARE_QUALITY_ENABLED, value).apply()

    /** Qualidade efetiva deste download, considerando a origem. */
    fun qualityFor(fromShare: Boolean): Quality =
        if (fromShare && shareQualityEnabled && shareQualityKey.isNotBlank()) {
            Quality.byKey(shareQualityKey)
        } else {
            effectiveQuality()
        }

    /** Ultima pasta valida escolhida pelo usuario. */
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
     * Os ultimos downloads (ate 10): o link E o arquivo.
     *
     * O link serve para baixar de novo; o arquivo e o que o botao de
     * compartilhar entrega. Guardar so o link obrigaria a resolver e baixar
     * outra vez para compartilhar o que ja esta no aparelho.
     *
     * Serializado como `link|uri` por linha — simples, sem JSON, e um item sem
     * uri (download antigo) continua valido.
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
