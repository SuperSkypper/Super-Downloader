package dev.superskypper.superdownloader

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Link compartilhado -> lista de videos com as variantes de resolucao.
 *
 * O X nao publica MP4 de todas as resolucoes: a API de metadados devolve UM url,
 * normalmente 720x1280. As outras alturas vivem num master.m3u8 que fica ao lado
 * (`<base>/pl/avc1/master.m3u8`), cujas variantes apontam para `/pl/avc1/<W>x<H>/`.
 * Eh esse o caminho para descobrir o que existe e baixar a altura pedida; o MP4
 * direto da API e o ultimo recurso. O master fica cacheado por post para o
 * usuario trocar de resolucao sem refazer a consulta inteira.
 */
object Resolver {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; SM_M315F) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    private val urlRegex = Regex("""https?://[^\s"'<>]+""")
    private val tweetRegex = Regex("""(?:status|statuses)/(\d+)""")
    private val directRegex = Regex("""(?i)^https?://[^\s]+\.(mp4|webm|mov)(\?.*)?$""")

    private val ladders = ConcurrentHashMap<String, List<Variant>>()

    fun firstUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return urlRegex.find(text)?.value?.trimEnd('.', ',', ')')
    }

    fun resolve(rawUrl: String): List<MediaItem> {
        val url = rawUrl.trim()
        val tweetId = tweetRegex.find(url)?.groupValues?.get(1)

        if (tweetId != null) {
            val vx = runCatching { fromVx(tweetId) }.getOrNull()
            if (!vx.isNullOrEmpty()) return vx
            val fx = runCatching { fromFx(tweetId) }.getOrNull()
            if (!fx.isNullOrEmpty()) return fx
            throw ResolveException(
                "Could not read the video from this post. Check that it still exists and try again."
            )
        }

        if (directRegex.matches(url)) {
            val name = url.substringAfterLast('/').substringBefore('?').substringBeforeLast('.')
            return listOf(
                MediaItem(
                    variants = listOf(Variant(url, 0, 0)),
                    screenName = name.ifBlank { "video" },
                    mediaId = "",
                    durationMs = 0
                )
            )
        }

        throw ResolveException("This app handles X (Twitter) links and direct .mp4 files for now.")
    }

    // --------------------------------------------------------------- fontes

    private fun fromVx(tweetId: String): List<MediaItem> {
        val root = JSONObject(httpGet("https://api.vxtwitter.com/i/status/$tweetId"))
        val screen = root.optString("user_screen_name").ifBlank { "video" }
        val media = root.optJSONArray("media_extended") ?: return emptyList()
        val out = ArrayList<MediaItem>()
        for (i in 0 until media.length()) {
            val m = media.optJSONObject(i) ?: continue
            val type = m.optString("type")
            if (type != "video" && type != "gif") continue
            val direct = m.optString("url")
            if (direct.isBlank()) continue
            out.add(
                MediaItem(
                    variants = withLadder(direct),
                    screenName = screen,
                    mediaId = m.optString("id_str"),
                    durationMs = m.optLong("duration_millis", 0L)
                )
            )
        }
        return out
    }

    private fun fromFx(tweetId: String): List<MediaItem> {
        val tweet = JSONObject(httpGet("https://api.fxtwitter.com/i/status/$tweetId")).optJSONObject("tweet")
            ?: return emptyList()
        val screen = tweet.optJSONObject("author")?.optString("screen_name").orEmpty().ifBlank { "video" }
        val videos = tweet.optJSONObject("media")?.optJSONArray("videos") ?: return emptyList()
        val out = ArrayList<MediaItem>()
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            val direct = v.optString("url")
            if (direct.isBlank()) continue
            out.add(
                MediaItem(
                    variants = withLadder(direct),
                    screenName = screen,
                    mediaId = v.optString("id"),
                    durationMs = 0
                )
            )
        }
        return out
    }

    /** O MP4 direto sempre entra na lista, como ultimo recurso. */
    private fun withLadder(direct: String): List<Variant> {
        val ladder = ladderFor(direct)
        if (ladder.any { it.url == direct }) return ladder
        return ladder + Variant(direct, 0, 0)
    }

    // -------------------------------------------------------------- escada

    private fun ladderFor(directUrl: String): List<Variant> {
        val base = masterBase(directUrl) ?: return emptyList()
        ladders[base]?.let { return it }

        val master = runCatching { httpGet("$base/pl/avc1/master.m3u8") }.getOrNull()
            ?: runCatching { httpGet("$base/master.m3u8") }.getOrNull()
            ?: return emptyList()

        val variants = parseMaster(master, base)
        if (variants.isNotEmpty()) ladders[base] = variants
        return variants
    }

    private fun masterBase(directUrl: String): String? {
        val marker = "/vid/avc1/"
        val idx = directUrl.indexOf(marker)
        if (idx > 0) return directUrl.substring(0, idx)
        if (directUrl.contains("/pl/") && directUrl.contains("master.m3u8")) {
            return directUrl.substringBefore("/pl/")
        }
        return null
    }

    private fun parseMaster(body: String, base: String): List<Variant> {
        val lines = body.lines().map { it.trim() }
        val out = ArrayList<Variant>()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val height = Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            val full = when {
                uri.startsWith("http") -> uri
                uri.startsWith("/") -> "https://video.twimg.com$uri"
                else -> "$base/$uri"
            }
            out.add(Variant(full, height, (bandwidth / 1000).toInt()))
        }
        return out
    }

    // ----------------------------------------------------------------- net

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code at $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
