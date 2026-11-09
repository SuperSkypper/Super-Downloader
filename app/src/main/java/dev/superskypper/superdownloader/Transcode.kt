package dev.superskypper.superdownloader

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.Statistics
import java.util.concurrent.CountDownLatch
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/**
 * Re-encode do preset WhatsApp.
 *
 * O X publica no maximo ~1,9 Mbps em 720p, e 85 s disso dao ~20 MB. O limite do
 * WhatsApp para video enviado como midia nao e 16 MB: o FAQ oficial fala em
 * 64 MB em conexao lenta e 100 MB em conexao rapida, com teto de 720p. Usar o
 * numero errado fazia o app recomprimir video que cabia tranquilamente. A regra combinada: primeiro tenta caber mantendo
 * 720p, baixando o bitrate; so cai de resolucao quando nem um bitrate de piso
 * caberia. Duas ou tres passadas no maximo, medindo o arquivo de verdade entre
 * elas (o encoder nao entrega exatamente o bitrate pedido).
 */
object Transcode {

    // 62 MB: os 64 MB do WhatsApp em conexao lenta, com margem para o
    // container e para o arredondamento do encoder.
    const val WHATSAPP_LIMIT = 62_000_000L

    private const val MIN_KBPS = 250
    // 96k: 128k empurrava o arquivo para 64,15 MB, logo acima do teto real
    // do WhatsApp (64 MB) e forcava uma segunda passada inteira.
    private const val AUDIO_KBPS = 96

    data class Result(val file: File, val width: Int, val height: Int, val bytes: Long)

    fun shouldCompress(bytes: Long): Boolean = bytes > WHATSAPP_LIMIT

    /**
     * @param source arquivo baixado
     * @param durationSeconds duracao real do video
     * @param sourceShort short side da origem (para nao upscalear)
     */
    fun fitForWhatsApp(
        context: Context,
        source: File,
        durationSeconds: Long,
        onProgress: (Float) -> Unit
    ): Result {
        val dir = File(context.cacheDir, "wa")
        dir.mkdirs()
        // Restos de tentativas anteriores ocupavam centenas de MB no cache.
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val duration = if (durationSeconds > 0) durationSeconds else 1

        // Orcamento total do arquivo: sobra o audio e o resto e video.
        fun budgetKbps(): Int =
            (WHATSAPP_LIMIT * 8L / duration / 1000L).toInt() - AUDIO_KBPS

        // Se o orcamento ja nasce abaixo do piso, tenta UMA vez no piso: o
        // arquivo sai bem menor que o original, que e o melhor possivel para um
        // video longo demais, em vez de recusar e deixar os 300 MB intactos.
        // Teto no bitrate do PROPRIO original: pedir mais que isso infla o
        // arquivo (um video de 1.7 Mbps virou 59 MB ao pedir 5707k).
        val sourceKbps = (source.length() * 8L / duration / 1000L).toInt()
        var videoKbps = budgetKbps().coerceAtLeast(MIN_KBPS)
        if (sourceKbps > 0) videoKbps = videoKbps.coerceAtMost(sourceKbps)

        var best: Result? = null
        var hardware = true

        // A RESOLUCAO NAO MUDA: o video mantem o tamanho original e so o bitrate
        // cai. Reduzir para 480p/360p estragava a imagem sem garantia de caber,
        // porque o tamanho do arquivo depende do bitrate, nao do numero de pixels.
        // Cada tentativa mede o resultado real e corrige o alvo proporcionalmente,
        // ja que o x264 nao entrega o bitrate pedido com precisao.
        for (attempt in 0 until 3) {
            if (videoKbps < MIN_KBPS) break

            val target = File(dir, "fit_$attempt.mp4")
            if (target.exists()) target.delete()

            // Hardware primeiro (MediaCodec), software como rede de seguranca.
            var ok = false
            if (hardware) {
                ok = runCatching {
                    val silent = File(dir, "hw_$attempt.mp4")
                    // O MediaCodec em CBR entrega ~18% MAIS bitrate do que se pede
                    // (948k pedidos viraram 1122k reais): sem compensar, a primeira
                    // passada estourava o limite e o app recomecava o encode
                    // inteiro — o "Compressing em loop" que o usuario via.
                    HardwareTranscode.videoOnly(source, silent, videoKbps * 84 / 100) {
                        onProgress(it * 0.92f)
                    }
                    val remuxed = remuxWithAudio(silent, source, target)
                    runCatching { silent.delete() }
                    if (!remuxed) throw IllegalStateException("remux failed")
                }.isSuccess
                if (!ok) {
                    // Aparelho sem encoder H.264 compativel: o resto vai em x264.
                    hardware = false
                    runCatching { target.delete() }
                }
            }

            if (!ok) {
                val command = buildString {
                    append("-y -i ")
                    append(quote(source.absolutePath))
                    // `ultrafast` no software: ~2-3x mais rapido que `veryfast`,
                    // que ja era necessario (no preset padrao, 0.2x).
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

            // UMA passada e o resultado. O encoder de hardware deste aparelho
            // entrega ~1 Mbps mesmo quando se pede 800k, entao uma segunda passada
            // nao aproxima do alvo — ela so refaz minutos de encode e aparecia
            // como "Compressing" em loop. Aceita qualquer resultado menor que o
            // original; o tamanho final vai na tela.
            if (size in 1 until source.length()) {
                best?.file?.let { runCatching { it.delete() } }
                return Result(target, 0, 0, size)
            }

            // So continua quando o encoder devolveu algo inutil (maior que a
            // origem): aí vale tentar de novo com outro bitrate.
            if (best == null || size < best!!.bytes) {
                best?.file?.let { runCatching { it.delete() } }
                best = Result(target, 0, 0, size)
            } else {
                target.delete()
            }
            videoKbps = videoKbps * 8 / 10
        }

        // Nenhuma tentativa coube: devolve a menor. Menor que o original ainda
        // e melhor que o original, e a tela avisa o tamanho final.
        best?.let { return it }
        throw IllegalStateException("could not compress the video to fit WhatsApp")
    }

    /**
     * Extrai so a faixa de audio do video baixado, em M4A (AAC).
     *
     * Baixar o MP4 inteiro e descartar o video e mais simples e mais confiavel
     * que montar o audio por HLS: o X publica a faixa de audio separada, mas
     * juntar os fragmentos no aparelho ja falhou em posts com duracao estranha.
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
            // A faixa pode estar num codec que nao copia para m4a; reencoda.
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
     * Junta o video transcodificado no hardware com o audio ORIGINAL do arquivo,
     * sem reencodar (`-c copy`): leva menos de um segundo e preserva o audio.
     * O `?` em `1:a:0?` faz o map ser opcional, para videos sem trilha de audio.
     */
    private fun remuxWithAudio(video: File, source: File, target: File): Boolean {
        val command = "-y -i ${quote(video.absolutePath)} -i ${quote(source.absolutePath)}" +
            " -map 0:v:0 -map 1:a:0? -c copy -movflags +faststart ${quote(target.absolutePath)}"
        val code = exec(command, 0) { }
        return ReturnCode.isSuccess(code) && target.length() > 0
    }

    /**
     * Roda o ffmpeg reportando progresso pela posicao do timestamp processado.
     *
     * `FFmpegKit.execute` e sincrono e nao chama callback nenhum, entao a barra
     * ficava parada durante toda a compressao — o usuario nao tinha como saber
     * que o app estava trabalhando. `executeAsync` com StatisticsCallback
     * entrega `time` em ms, que dividido pela duracao da o percentual.
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
