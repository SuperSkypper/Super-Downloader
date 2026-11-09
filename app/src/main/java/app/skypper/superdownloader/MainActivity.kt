package app.skypper.superdownloader

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Duas abas, como o Super Capture: Download (link, qualidade, progresso) e
 * Settings (pasta, qualidade automatica).
 *
 * O desenho e o do Super Capture de proposito: pagina com 10dp de margem,
 * titulo de secao solto em caixa alta acima de um cartao `bg_card`, cada linha
 * num slot de 42dp, chips de 34dp (selecionado = accent cheio com texto escuro)
 * e a tab bar como overlay com hairline, indicador `accentDim` contornado e
 * icone + rotulo que acendem juntos.
 */
class MainActivity : AppCompatActivity() {

    private enum class Action { CANCEL, SHARE, RETRY, DOWNLOAD, IDLE }

    private lateinit var palette: Palette
    private lateinit var prefs: Prefs

    // Estrutura
    private lateinit var pageView: LinearLayout

    // Aba Download
    private lateinit var linkField: EditText
    private lateinit var qualityRow: LinearLayout
    private lateinit var historyBlock: LinearLayout
    private lateinit var historyScroll: ScrollView
    private var lastRow: View? = null
    private lateinit var historyCard: LinearLayout
    private lateinit var qualityScroll: HorizontalScrollView
    private lateinit var customRow: LinearLayout
    private lateinit var customScroll: HorizontalScrollView
    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var progressBar: ProgressStrip
    private lateinit var actionButton: TextView
    private lateinit var shareButton: TextView

    // Aba Settings
    private lateinit var folderValue: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val folderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { tree -> onFolderPicked(tree) }

    @Volatile private var cancelled = false
    @Volatile private var busy = false

    private var downloaded = ArrayList<Uri>()
    private var thumbPath: String = ""
    private var lastBytes: Long = 0L
    private var fromShare: Boolean = false

    /** Link ja enviado ao download. Sobrevive a recriacao da Activity. */
    private var handledUrl: String? = null
    private var compressStart: Long = 0L
    private var lastUrl: String? = null
    private var action = Action.IDLE
    private var availableHeights: List<Int> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = Palette(this)
        prefs = Prefs(this)
        setContentView(buildUi())
        prefsLoadState()
        showShareButton()

        // O app e resizeable e nao declara configChanges: entrar em tela dividida,
        // girar ou trocar o tamanho da janela DESTROI e recria esta Activity. Sem
        // este marcador, o onCreate relia o link do intent e disparava o download
        // outra vez a cada recriacao — o "rebaixando" que aparecia ao sair e
        // voltar do foco.
        handledUrl = savedInstanceState?.getString(STATE_HANDLED_URL)
        val restoring = savedInstanceState != null

        val incoming = urlFrom(intent)
        val previousText = savedInstanceState?.getString(STATE_LINK_TEXT).orEmpty()
        val typed = previousText.ifBlank { incoming.orEmpty() }
        if (typed.isNotBlank()) {
            linkField.setText(typed)
            linkField.setSelection(typed.length)
        }
        this.fromShare = intent?.action == Intent.ACTION_SEND

        if (restoring) {
            // Recriacao: repinta o estado e so baixa se o link for outro.
            lastUrl = handledUrl ?: incoming
            if (incoming != null && incoming != handledUrl) {
                handle(incoming, this.fromShare)
            } else {
                idle()
            }
        } else {
            handle(incoming, this.fromShare)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val url = urlFrom(intent) ?: return
        linkField.setText(url)
        handledUrl = url
        handle(url, intent.action == Intent.ACTION_SEND)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        handledUrl?.let { outState.putString(STATE_HANDLED_URL, it) }
        outState.putString(STATE_LINK_TEXT, linkField.text?.toString().orEmpty())
    }

    override fun onDestroy() {
        cancelled = true
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- intent

    private fun urlFrom(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> Resolver.firstUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
    }

    /**
     * @param isShare true quando o link veio da folha de compartilhamento. Vem
     * por parametro porque `onNewIntent` NAO atualiza `Activity.intent`: ler a
     * propriedade ali devolvia a action da primeira abertura e um share
     * posterior era tratado como link colado (ou o contrario).
     */
    private fun handle(url: String?, isShare: Boolean) {
        fromShare = isShare
        if (url == null) {
            idle()
            return
        }
        lastUrl = url
        if (busy) cancelled = true
        if (prefs.autoQuality) start(url) else askQuality(url)
    }

    // ------------------------------------------------------------ estrutura

    /**
     * Uma pagina so. A aba Settings saiu: a pasta de destino virou uma linha na
     * tela principal e o que era configuracao de share mora na engrenagem da
     * fileira de qualidade.
     */
    private fun buildUi(): FrameLayout {
        val root = FrameLayout(this).apply { setBackgroundColor(palette.background) }
        pageView = buildDownloadPage()
        root.addView(
            pageView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        return root
    }

    // ---------------------------------------------------------- aba Download

    private fun buildDownloadPage(): LinearLayout {
        val page = page()

        // HISTORY ----------------------------------------------------------
        historyBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        historyBlock.addView(sectionTitle("HISTORY"))
        historyCard = card()
        // Quadrado grande e fixo: cabe as tres linhas de historico com os dois
        // botoes, e nao muda de altura quando o primeiro item chega.
        // Sem altura fixa: o History toma todo o espaco que sobra entre o status
        // e os controles de baixo. Rola, e os itens crescem DE BAIXO PARA CIMA
        // (`Gravity.BOTTOM` no cartao + ordem invertida no render): o mais novo e
        // o mais perto do polegar, como no resto do app.
        historyCard.gravity = Gravity.BOTTOM
        historyScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(historyCard, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        historyBlock.addView(historyScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // QUALITY ----------------------------------------------------------
        val qualityBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        qualityBlock.addView(sectionTitle("TEMPLATES"))
        qualityRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Quality.ALL.forEach { preset ->
            val chip = chip(preset.label) {
                prefs.quality = preset
                customScroll.visibility =
                    if (preset.preset == QualityPreset.CUSTOM) View.VISIBLE else View.GONE
                paintChips()
                onQualityPicked()
            }
            qualityRow.addView(chip, chipParams())
        }
        qualityScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(qualityRow)
        }
        qualityBlock.addView(qualityScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Escada do Custom: so com Custom selecionado, e so com as alturas que
        // o post realmente tem.
        customRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Quality.LADDER.forEach { level ->
            val chip = chip(level.label) {
                prefs.customHeightKey = level.key
                paintChips()
                onQualityPicked()
            }
            customRow.addView(chip, chipParams())
        }
        customScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(customRow)
            visibility = View.GONE
        }
        qualityBlock.addView(customScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        // LINK -------------------------------------------------------------
        // Barra do Super Capture (`timeLogBar`): campo de 42dp com `bg_field` e
        // um botao accent ao lado. Aqui o botao e Paste, com glifo e palavra.
        val linkBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        linkBlock.addView(sectionTitle("LINK"))
        val linkBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(10), 0)
            // Sem padding a esquerda: o campo entra na mesma linha dos outros
            // blocos. A direita fecha na margem da pagina.
        }
        linkBar.addView(linkField())
        linkBar.addView(pasteButton())
        linkBlock.addView(linkBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // STATUS -----------------------------------------------------------
        // So o estado do download: titulo, barra e a linha de detalhe (bytes,
        // aviso de compressao). A instrucao "cole um link e escolha a
        // qualidade" saiu daqui: o proprio campo e os chips dizem isso.
        // Barra de status GROSSA: o estado centralizado no meio dela, a barra
        // de progresso como fundo e as estatisticas de bytes logo abaixo.
        val statusCard = FrameLayout(this).apply {
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
            minimumHeight = dp(STATUS_HEIGHT_DP)
        }

        // Fundo: a barra de progresso ocupa o bloco inteiro (grossa).
        // View propria, nao ProgressBar: ver ProgressStrip.kt.
        progressBar = ProgressStrip(this)
        // Altura FIXA, nao MATCH_PARENT: dentro de um FrameLayout wrap_content a
        // View sem tamanho intrinseco recebe um spec AT_MOST e cresce ate ocupar
        // a tela toda (a barra azul engoliu a pagina antes desta correcao).
        statusCard.addView(progressBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(STATUS_HEIGHT_DP)
        ))

        val statusColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        statusView = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(palette.text)
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD
            )
        }
        statusColumn.addView(statusView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        detailView = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(palette.muted)
            // Fora do bloco de status: so aparece quando ha um erro para explicar.
            visibility = View.GONE
        }
        // Detalhe dentro do bloco, embaixo do titulo: bytes baixados, quanto
        // falta, percentual e a causa de um erro. Fica oculto quando nao ha o
        // que dizer (estado Waiting).
        statusColumn.addView(detailView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) })
        detailView.visibility = View.GONE
        statusCard.addView(statusColumn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL
        ))

        // ACAO (largura total, no polegar) --------------------------------
        // Mesmo desenho do botao Paste: accent cheio, texto on_accent. Ele e a
        // acao principal da tela, entao nao pode ser o contorno secundario.
        actionButton = outlinedButton("Download").apply {
            background = palette.roundRect(palette.accent, dp(12).toFloat())
            setTextColor(palette.onAccent)
            setOnClickListener { onAction() }
        }

        // De baixo para cima: o link e o primeiro elemento da base, a
        // qualidade sobe acima dele e o history fica no topo.
        // Ordem na tela, de cima para baixo: STATUS, (respiro), HISTORY,
        // QUALITY, LINK, ACAO. O status fica no topo a pedido; o resto desce
        // colado no rodape porque o spacer com peso 1 come o espaco livre.
        // A pasta e a primeira coisa da tela: sem titulo de secao e sem
        // descricao, so o rotulo "Choose Download Folder" e o template ativo.
        val destBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = palette.roundRect(palette.surface, dp(10).toFloat(), palette.accent, dp(1))
            setPadding(dp(10), 0, dp(10), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { folderLauncher.launch(null) }
        }
        destBar.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_folder)
                imageTintList = ColorStateList.valueOf(palette.accent)
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
            }
        )
        destBar.addView(
            TextView(this).apply {
                text = "Download Folder:"
                setTextColor(palette.text)
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(6) }
            }
        )
        folderValue = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(palette.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10) }
        }
        destBar.addView(folderValue)
        page.addView(destBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(42)
        ))

        page.addView(statusCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        // Share + engrenagem lado a lado, sempre visiveis. Share nasce
        // desabilitado (cinza) porque nao ha o que compartilhar; a engrenagem
        // fica aqui porque ela configura justamente o share.
        val shareRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        shareButton = outlinedButton("Share").apply {
            setOnClickListener { share() }
        }
        shareRow.addView(
            shareButton,
            LinearLayout.LayoutParams(0, dp(42), 1f)
        )
        val gearButton = FrameLayout(this).apply {
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_action)
            isClickable = true
            isFocusable = true
            setOnClickListener { openShareQuality() }
        }
        gearButton.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_gear)
                imageTintList = ColorStateList.valueOf(palette.muted)
                layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
            }
        )
        shareRow.addView(
            gearButton,
            LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(10) }
        )
        page.addView(shareRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(42)
        ).apply { topMargin = dp(10) })
        page.addView(historyBlock, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(10) })
        page.addView(qualityBlock, blockParams())
        page.addView(linkBlock, blockParams())
        page.addView(actionButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(42)
        ).apply { topMargin = dp(10) })
        // O botao de download fica imediatamente abaixo da barra do link: a
        // barra e o ultimo controle antes da acao principal.
        return page
    }

    /**
     * O campo de link, no desenho do `timeLogBar` do Super Capture: 42dp,
     * `bg_field`, texto 14sp com 12dp de respiro, uma linha so.
     */
    private fun linkField(): EditText = EditText(this).apply {
        hint = "Paste Link Here"
        setHintTextColor(palette.muted)
        setTextColor(palette.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(12), 0, dp(12), 0)
        maxLines = 1
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_DONE
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_field)
        layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(10) }
        setOnEditorActionListener { _, _, _ ->
            val typed = Resolver.firstUrl(text.toString().trim())
            if (typed != null) handle(typed, false) else idle()
            true
        }
        linkField = this
    }

    /**
     * Botao Paste: accent cheio, glifo e palavra, como o `saveButton` do
     * Super Capture (icone + texto num LinearLayout horizontal, nunca um Button
     * com compound drawable).
     */
    private fun pasteButton(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = palette.roundRect(palette.accent, dp(12).toFloat())
        isClickable = true
        isFocusable = true
        setPadding(dp(10), 0, dp(10), 0)
        // Largura fixa e enxuta: `WRAP_CONTENT` nao soma o padding do texto na
        // linha, e o botao fechava antes da margem; ja 120dp ficava largo demais
        // para um alvo de duas palavras. 88dp = 16 do glifo + 5 do vao + "Paste".
        layoutParams = LinearLayout.LayoutParams(dp(88), dp(42))
        setOnClickListener { pasteFromClipboard() }

        addView(
            ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_paste)
                imageTintList = ColorStateList.valueOf(palette.onAccent)
            },
            LinearLayout.LayoutParams(dp(16), dp(16))
        )
        addView(
            TextView(this@MainActivity).apply {
                text = "Paste"
                setTextColor(palette.onAccent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(5) }
        )
    }

    /** Cola o primeiro link da area de transferencia no campo, sem baixar. */
    private fun pasteFromClipboard() {
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clip?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        val url = Resolver.firstUrl(text)
        if (url == null) {
            setDetail("Nothing to paste: copy a link first.")
            return
        }
        linkField.setText(url)
        linkField.setSelection(url.length)
        setDetail("")
        setAction(Action.DOWNLOAD, "Download")
        lastUrl = url
    }

    // ---------------------------------------------------------- aba Settings

    private fun openShareQuality() {
        ShareQualityDialog(
            host = this,
            palette = palette,
            prefs = prefs,
            onChanged = { paintChips() }
        ).show()
    }

    // ------------------------------------------------------------- estado UI

    private fun prefsLoadState() {
        paintSettings()
        paintChips()
        renderHistory()
    }

    /** A linha de destino mostra o template ativo (o ultimo usado). */
    private fun paintSettings() {
        if (!::folderValue.isInitialized) return
        val label = prefs.destLabel.ifBlank { Downloader.DEFAULT_LABEL }
        folderValue.text = label
        folderValue.setTextColor(palette.text)
    }

    /**
     * Chip selecionado = accent cheio com texto escuro; os outros contornados.
     * A escada do Custom e cortada no que o post oferece.
     */
    private fun paintChips() {
        if (!::qualityRow.isInitialized) return
        val selectedPreset = prefs.quality.preset

        // So os chips: o ultimo filho da fileira e a engrenagem (FrameLayout).
        for (i in Quality.ALL.indices) {
            val chip = qualityRow.getChildAt(i) as? TextView ?: continue
            val isSelected = Quality.ALL[i].preset == selectedPreset
            chip.background = if (isSelected) {
                palette.roundRect(palette.accent, dp(12).toFloat())
            } else {
                ContextCompat.getDrawable(this, R.drawable.bg_action)
            }
            chip.setTextColor(if (isSelected) palette.onAccent else palette.muted)
        }

        if (::customRow.isInitialized && ::customScroll.isInitialized) {
            customScroll.visibility =
                if (selectedPreset == QualityPreset.CUSTOM) View.VISIBLE else View.GONE
            val offered = Quality.ladderFor(availableHeights.maxOrNull()).map { it.key }.toSet()
            for (i in 0 until customRow.childCount) {
                val chip = customRow.getChildAt(i) as TextView
                val level = Quality.LADDER[i]
                val isOffered = level.key in offered
                val isSelected = level.key == prefs.customHeightKey
                chip.visibility = if (isOffered) View.VISIBLE else View.GONE
                chip.background = if (isSelected) {
                    palette.roundRect(palette.accent, dp(12).toFloat())
                } else {
                    ContextCompat.getDrawable(this, R.drawable.bg_action)
                }
                chip.setTextColor(if (isSelected) palette.onAccent else palette.muted)
            }
        }
    }

    /**
     * History: os tres ultimos links. Cada linha mostra o link em uma linha, com
     * elipse no fim; tocar preenche o campo (nao baixa, para nao gastar dados
     * por engano). Sem itens, o bloco inteiro fica escondido.
     */
    /**
     * History: quadrado de altura fixa com as tres ultimas linhas, cada uma com
     * os dois botoes (copiar o link / compartilhar o arquivo baixado).
     *
     * Vazio mostra "No History" no lugar das linhas, mas os botoes continuam ali:
     * o bloco nao muda de forma conforme o uso.
     */
    private fun renderHistory() {
        if (!::historyCard.isInitialized) return
        // Guarda o topo visivel para nao pular quando a lista so foi repintada.
        val previousTop = historyScroll.scrollY
        val previousCount = historyCard.childCount
        historyCard.removeAllViews()

        val items = prefs.history
        if (items.isEmpty()) {
            historyCard.addView(
                TextView(this).apply {
                    text = "No History"
                    setTextColor(palette.muted)
                    textSize = 13f
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(80)
                )
            )
            return
        }

        lastRow = null
        items.reversed().forEach { entry ->
            val hasThumb = entry.thumb.isNotBlank() && java.io.File(entry.thumb).exists()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = palette.roundRect(palette.surface, dp(10).toFloat(), palette.border, dp(1))
                setPadding(dp(if (hasThumb) 6 else 10), dp(4), dp(4), dp(4))
            }

            // Quadrado com o primeiro frame: e o unico jeito de saber QUAL video
            // e aquela linha sem abrir o link.
            if (entry.thumb.isNotBlank()) {
                val file = java.io.File(entry.thumb)
                if (file.exists()) {
                    row.addView(
                        ImageView(this@MainActivity).apply {
                            setImageBitmap(
                                android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                            )
                            scaleType = ImageView.ScaleType.CENTER_CROP
                        },
                        LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(10) }
                    )
                }
            }

            // O link, clicavel: toca e preenche o campo (nao baixa sozinho).
            row.addView(
                TextView(this).apply {
                    text = entry.link
                    setTextColor(palette.text)
                    textSize = 12f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        linkField.setText(entry.link)
                        linkField.setSelection(entry.link.length)
                        lastUrl = entry.link
                        setAction(Action.DOWNLOAD, "Download")
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(4) }
            )

            // Copiar o link.
            row.addView(
                rowIcon(R.drawable.ic_copy) {
                    val clip = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clip?.setPrimaryClip(android.content.ClipData.newPlainText("link", entry.link))

                }
            )

            // Compartilhar o arquivo ja baixado.
            val shareIcon = rowIcon(R.drawable.ic_share) {
                shareSingle(Uri.parse(entry.uri))
            }
            if (entry.uri.isBlank()) {
                shareIcon.alpha = 0.35f
                shareIcon.isEnabled = false
            }
            row.addView(shareIcon)

            // Linha mais alta que o rowSlot padrao (42dp): a miniatura de 64dp
            // precisa de espaco e o rowSlot cortava.
            historyCard.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(if (hasThumb) HISTORY_ROW_DP else 42)
                ).apply { bottomMargin = dp(6) }
            )
            lastRow = row
        }

        val newItemArrived = historyCard.childCount > previousCount

        // O item mais novo entra embaixo, entao a vista padrao e o FIM da lista:
        // toda chega nova rola para baixo sozinha e o ultimo fica visivel. Rolar
        // para cima e a excecao (o usuario escolhe), e essa posicao e preservada
        // quando o render acontece por outro motivo.
        historyScroll.post {
            if (previousTop > 0 && !newItemArrived) {
                // Repintura sem novidade (troca de qualidade, volta de foco):
                // devolve a posicao que o usuario tinha escolhido.
                historyScroll.scrollTo(0, previousTop)
            } else {
                // Item novo entra embaixo: a vista padrao e o fim da lista.
                historyScroll.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    /** Icone-botao de 32dp usado nas linhas do History. */
    private fun rowIcon(iconRes: Int, onClick: () -> Unit): FrameLayout {
        val button = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(42))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        button.addView(
            ImageView(this).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(palette.muted)
                layoutParams = FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER)
            }
        )
        return button
    }

    private fun shareSingle(uri: Uri) {
        if (uri.toString().isBlank()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = Downloader.MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(send, "Share Video")) }
    }

    private fun onQualityPicked() {
        val chosen = prefs.effectiveQuality()
        setDetail(
            when {
                chosen.isAudioOnly -> "Audio Only: extracts the track, no video."
            chosen.isWhatsApp -> "WhatsApp: up to 720p, compressed to fit the send limit."
                chosen.isHighest -> "Highest Quality: the largest resolution the post has."
                else -> "Custom ${chosen.label}: downloads up to ${chosen.label} when the post has it."
            }
        )
    }

    // --------------------------------------------------------------- estados

    private fun idle() {
        busy = false
        progressBar.progress = 0f
        statusView.text = "Waiting For Download"
        statusView.setTextColor(palette.text)
        setDetail("")
        setAction(Action.IDLE, "Download")
    }

    private fun start(url: String) {
        lastUrl = url
        handledUrl = url
        busy = true
        cancelled = false
        downloaded = ArrayList()
        val quality = prefs.qualityFor(fromShare)
        progressBar.progress = 0f
        statusView.text = "Downloading"
        statusView.setTextColor(palette.text)
        setDetail("Reading the link…")
        hideShareButton()
        setAction(Action.CANCEL, "Cancel")

        worker.execute {
            try {
                val items = Resolver.resolve(url)
                if (items.isEmpty()) throw ResolveException("This post has no video to download.")
                availableHeights = items.flatMap { item -> item.variants.map { it.height } }
                    .filter { it > 0 }.distinct().sortedDescending()
                post { paintChips() }

                val taken = mutableSetOf<String>()
                val heading = "Downloading"
                post {
                    statusView.text = heading
                    setDetail("Connecting…")
                }
                items.forEachIndexed { index, item ->
                    if (cancelled) throw CancelledException()
                    val variant = Downloader.pick(item, quality)
                        ?: throw ResolveException("No resolution available for this post.")
                    val name = Downloader.fileName(item, quality, taken)
                    if (items.size > 1) {

                    }
                    val mime = if (quality.isAudioOnly) Downloader.MIME_AUDIO else Downloader.MIME
                    val target = Downloader.download(
                        context = this,
                        variant = variant,
                        displayName = name,
                        mime = mime,
                        dest = dest(),
                        onProgress = { bytes, total -> progress(bytes, total) },
                        isCancelled = { cancelled }
                    )
                    if (variant.height in 1 until 720) {
                        post { setDetail("This post only has up to ${variant.height}p.") }
                    }

                    var finalUri = target.uri
                    if (quality.isAudioOnly) {
                        post {
                            statusView.text = "Compressing"
                            setDetail("Extracting the audio…")
                        }
                        finalUri = toAudioOnly(target)
                    }
                    if (quality.isWhatsApp && Transcode.shouldCompress(target.bytes)) {
                        post {
                            statusView.text = "Compressing"
                            statusView.setTextColor(palette.text)
                            progressBar.progress = 0f
                            setDetail("${size(target.bytes)} is over the ${Transcode.WHATSAPP_LIMIT / 1_000_000} MB limit, reducing…")
                        }
                        compressStart = System.currentTimeMillis()
                        finalUri = transcodeForWhatsApp(target)
                    }
                    lastBytes = runCatching {
                        contentResolver.openAssetFileDescriptor(finalUri, "r")?.use { it.length }
                    }.getOrNull()?.takeIf { it > 0 } ?: target.bytes
                    downloaded.add(finalUri)
                }

                // Miniatura do primeiro frame: e o que identifica o video no
                // History. Feita aqui (fora da UI) e nao a cada render.
                thumbPath = downloaded.firstOrNull()
                    ?.let { Thumbs.make(this, it) }
                    .orEmpty()
                finished()
            } catch (e: CancelledException) {
                post {
                    busy = false
                    statusView.text = "Waiting For Download"
                    statusView.setTextColor(palette.text)
                    setDetail("Nothing was saved.")
                    progressBar.progress = 0f
                    setAction(Action.RETRY, "Try Again")
                }
            } catch (t: Throwable) {
                post { failed(t.message ?: t.javaClass.simpleName) }
            }
        }
    }

    /** Modo manual: o link fica parado ate o usuario escolher a qualidade. */
    private fun askQuality(url: String) {
        busy = false
        cancelled = false
        progressBar.progress = 0f
        statusView.text = "Waiting For Download"
        statusView.setTextColor(palette.text)
        val chosen = prefs.effectiveQuality()
        setDetail("")
        setAction(Action.DOWNLOAD, "Download")

        worker.execute {
            val heights = runCatching { Resolver.resolve(url) }
                .getOrNull()
                ?.flatMap { item -> item.variants.map { it.height } }
                ?.filter { it > 0 }
                ?.distinct()
                ?.sortedDescending()
                .orEmpty()
            if (heights.isNotEmpty()) {
                availableHeights = heights
                post { paintChips() }
            }
        }
    }

    /**
     * Share fica sempre no lugar; o que muda e o estado. Sem arquivo ele fica
     * contornado e cinza (isEnabled = false), com arquivo vira accent cheio.
     */
    private fun showShareButton() {
        if (!::shareButton.isInitialized) return
        val hasFile = downloaded.isNotEmpty()
        shareButton.isEnabled = hasFile
        shareButton.alpha = if (hasFile) 1f else 0.4f
        shareButton.background = if (hasFile) {
            palette.roundRect(palette.accent, dp(12).toFloat())
        } else {
            ContextCompat.getDrawable(this, R.drawable.bg_action)
        }
        shareButton.setTextColor(if (hasFile) palette.onAccent else palette.muted)
    }

    /** Ao comecar outro download o arquivo anterior deixa de valer. */
    private fun hideShareButton() {
        if (!::shareButton.isInitialized) return
        downloaded = ArrayList()
        showShareButton()
    }

    /**
     * Para onde este download vai. `fromShare` distingue um link que chegou por
     * share de um que foi colado na tela: so o share respeita o override.
     */
    private fun dest(): Dest =
        if (prefs.destTree.isBlank()) Dest.NONE else Dest(prefs.destTree, prefs.destLabel)


    private fun progress(bytes: Long, total: Long) {
        post {
            if (total > 0) {
                val percent = (bytes * 1000 / total).toInt().coerceIn(0, 1000)
                progressBar.progress = percent / 1000f
                val left = (total - bytes).coerceAtLeast(0)
                setDetail("${percent / 10}% • ${size(bytes)} of ${size(total)} • ${size(left)} left")
            } else {
                // Sem Content-Length: mostra o que ja veio e nada de barra.
                progressBar.progress = 0f
                setDetail("${size(bytes)} downloaded")
            }
        }
    }

    private fun finished() {
        lastUrl?.let {
            prefs.pushHistory(it, downloaded.firstOrNull()?.toString().orEmpty(), thumbPath)
        }
        post {
            busy = false
            renderHistory()
            progressBar.progress = 1f
            statusView.text = "Download Complete"
            statusView.setTextColor(palette.text)
            val total = downloaded.size
            setDetail(
                "$total ${if (total > 1) "files" else "file"} • " +
                    "${size(lastBytes)} • ${prefs.destLabel.ifBlank { Downloader.DEFAULT_LABEL }}"
            )
            showShareButton()
            // Concluido nao tem o que cancelar: o botao do rodape volta a ser
            // Download, para comecar outro.
            setAction(Action.IDLE, "Download")
            // Abre a folha de compartilhamento sozinha quando o download vem de
            // um share (o usuario ja estava compartilhando algo, entao o passo
            // seguinte e o mesmo gesto). Um link colado na tela nao abre nada:
            // ali o share e uma escolha, e o botao Share fica disponivel.
            if (fromShare) share()
        }
    }

    private fun failed(message: String) {
        busy = false
        progressBar.progress = 0f
        statusView.text = "Waiting For Download"
        statusView.setTextColor(palette.danger)
        setDetail(message)
        setAction(Action.RETRY, "Try Again")
    }

    // ----------------------------------------------------------- transcode

    /**
     * Troca o MP4 baixado pelo M4A extraido. O video sai do disco: o preset e
     * "so audio", guardar os dois arquivos encheria a pasta.
     */
    private fun toAudioOnly(target: Downloader.Target): Uri {
        val local = Downloader.localCopy(this, target.uri)
        val result = try {
            Transcode.extractAudio(this, local) { fraction ->
                post {
                    progressBar.progress = fraction
                    setDetail("Extracting the audio… ${(fraction * 100).toInt()}%")
                }
            }
        } finally {
            runCatching { local.delete() }
        }
        val audioTarget = target.copy(name = target.name.substringBeforeLast('.') + ".m4a")
        val uri = Downloader.replaceWith(this, audioTarget, result.file, dest(),
            mime = Downloader.MIME_AUDIO)
        post {
            setDetail("${size(result.bytes)} of audio")
        }
        return uri
    }

    private fun transcodeForWhatsApp(target: Downloader.Target): Uri {
        // Duracao e lado curto vem do ARQUIVO, nao do modelo do post.
        val probe = Downloader.probe(this, target.uri)
        val seconds = ((probe?.durationMs ?: 0L) / 1000).coerceAtLeast(1)
        // A copia no cache existe so para o FFmpeg ler; sem apagar, cada
        // compressao deixava o arquivo original (dezenas de MB) no cache.
        val local = Downloader.localCopy(this, target.uri)
        val result = try {
            Transcode.fitForWhatsApp(
                context = this,
                source = local,
                durationSeconds = seconds
            ) { fraction ->
                // Mesma barra do download, agora enchendo enquanto o ffmpeg
                // processa: sem isso o bloco dizia "Compressing" com a barra
                // parada em 100% e nada indicava progresso.
                post {
                    progressBar.progress = fraction
                    // O titulo ja diz "Compressing": a linha de baixo mostra so o
                    // andamento, sem repetir a palavra.
                    val percent = (fraction * 100).toInt()
                    // ETA pela velocidade MEDIDA: uma compressao longa levava
                    // minutos sem qualquer previsao e parecia travada.
                    val elapsed = (System.currentTimeMillis() - compressStart) / 1000
                    val eta = if (fraction > 0.02f) (elapsed / fraction * (1 - fraction)).toInt() else 0
                    val left = when {
                        eta <= 5 -> ""
                        eta < 60 -> " • ${eta}s left"
                        else -> " • ${eta / 60} min left"
                    }
                    setDetail("$percent% • reducing to ${Transcode.WHATSAPP_LIMIT / 1_000_000} MB$left")
                }
            }
        } finally {
            runCatching { local.delete() }
        }
        // Se a "compressao" nao diminuiu nada, fica o arquivo original: trocar
        // por um maior so gastaria espaco e perderia qualidade de graca.
        if (result.bytes >= target.bytes) {
            post { setDetail("${size(target.bytes)} already fits, nothing to reduce") }
            return target.uri
        }
        val uri = Downloader.replaceWith(this, target, result.file, dest())
        post {
            setDetail("${size(result.bytes)} after compressing for WhatsApp")
        }
        return uri
    }

    // ----------------------------------------------------------------- share

    private fun share() {
        if (downloaded.isEmpty()) return
        val send = if (downloaded.size > 1) {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = Downloader.MIME
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(downloaded))
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = Downloader.MIME
                putExtra(Intent.EXTRA_STREAM, downloaded.first())
            }
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share Video"))
    }

    // ------------------------------------------------------------- pasta SAF

    private fun onFolderPicked(tree: Uri?) {
        if (tree == null) return
        runCatching {
            contentResolver.takePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val label = labelFor(tree)
        prefs.saveDest(tree.toString(), label)
        paintSettings()
        paintChips()
    }

    private fun labelFor(tree: Uri): String {
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull().orEmpty()
        val tail = treeId.substringAfter(':').substringAfterLast('/')
        if (tail.isNotBlank()) return tail
        return runCatching {
            contentResolver.query(
                DocumentsContract.buildDocumentUriUsingTree(tree, treeId),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull().orEmpty().ifBlank { "Selected Folder" }
    }

    // ------------------------------------------------------- pecas do design

    /**
     * Pagina com o status no topo e os controles colados no rodape.
     *
     * Os apps sao desenhados de baixo para cima, entao o link (e o botao) ficam
     * no polegar; o status e a excecao pedida: ele fica no topo, sempre visivel,
     * separado do resto por um spacer com peso. Sem o spacer o conteudo sobe e
     * o botao de acao nao encosta no fim da tela.
     */
    private fun page(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(palette.background)
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }

    /**
     * Titulo de secao do Super Capture: 12sp, caixa alta, bold, letterSpacing
     * 0.09, muted, `padding(10, 2, 10, 6)` e 6dp abaixo. Ele fica SOLTO acima do
     * cartao, nunca dentro dele — e o que faz a pagina ler como tela de app e
     * nao como painel de controle.
     */
    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(palette.muted)
        textSize = 12f
        isAllCaps = true
        letterSpacing = 0.09f
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD
        )
        setPadding(dp(10), dp(2), dp(10), dp(6))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
        setPadding(dp(6), dp(6), dp(6), dp(6))
    }

    private fun rowSlot(child: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(4), dp(10), dp(4))
        addView(child, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)))
    }

    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(palette.muted)
        textSize = 13f
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    private fun chip(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(12), 0)
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_action)
        setTextColor(palette.muted)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun chipParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)
    ).apply { marginEnd = dp(10) }

    private fun outlinedButton(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_action)
        setTextColor(palette.text)
        isClickable = true
        isFocusable = true
    }

    /** 10dp entre blocos principais, a regua do Super Capture. */
    private fun blockParams(topMarginDp: Int = 10): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(topMarginDp) }

    private fun setAction(kind: Action, label: String) {
        action = kind
        // O botao fica sempre visivel: ocioso ele E o "Download", que le o link
        // do campo; nos outros estados vira Cancel / Share Again / Try Again.
        actionButton.visibility = View.VISIBLE
        actionButton.text = label
        actionButton.alpha = 1f
        when (kind) {
            Action.CANCEL -> {
                actionButton.setTextColor(palette.danger)
                actionButton.background = ContextCompat.getDrawable(this, R.drawable.bg_action)
            }
            else -> {
                actionButton.setTextColor(palette.onAccent)
                actionButton.background = palette.roundRect(palette.accent, dp(12).toFloat())
            }
        }
    }

    private fun onAction() {
        when (action) {
            Action.DOWNLOAD -> {
                val typed = Resolver.firstUrl(linkField.text.toString().trim()) ?: lastUrl
                if (typed != null) start(typed) else idle()
            }
            Action.CANCEL -> {
                cancelled = true
                statusView.text = "Cancelling"
                setDetail("stopping the download…")
            }
            Action.SHARE -> {
                share()
                idle()
                hideShareButton()
            }
            Action.RETRY -> lastUrl?.let { handle(it, fromShare) } ?: idle()
            Action.IDLE -> {
                val typed = Resolver.firstUrl(linkField.text.toString().trim()) ?: lastUrl
                if (typed != null) {
                    linkField.setText(typed)
                    handle(typed, false)
                } else {
                    setDetail("Paste a link first.")
                }
            }
        }
    }

    // --------------------------------------------------------------- helpers

    /** Escreve a linha de detalhe e so a mostra quando ha conteudo. */
    private fun setDetail(text: String) {
        detailView.text = text
        detailView.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun post(block: () -> Unit) {
        ui.post { if (!isFinishing && !isDestroyed) block() }
    }

    private fun size(bytes: Long): String = when {
        bytes <= 0 -> "0 MB"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** Altura do bloco de status: barra grossa com o texto por cima. */
        private const val STATUS_HEIGHT_DP = 54

        private const val STATE_HANDLED_URL = "handled_url"
        private const val STATE_LINK_TEXT = "link_text"

        /** Altura da linha do History com miniatura (64dp de imagem + respiro). */
        private const val HISTORY_ROW_DP = 72
    }
}
