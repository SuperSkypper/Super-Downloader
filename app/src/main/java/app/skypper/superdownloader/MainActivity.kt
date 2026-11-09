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
 * Two tabs, like Super Capture: Download (link, quality, progress) and
 * Settings (folder, automatic quality).
 *
 * The layout is Super Capture's on purpose: page with a 10dp margin,
 * section title loose in caps above a `bg_card` card, each row
 * in a 42dp slot, 34dp chips (selected = solid accent with dark text)
 * and the tab bar as an overlay with a hairline, `accentDim` outlined indicator and
 * icon + label that light up together.
 */
class MainActivity : AppCompatActivity() {

    private enum class Action { CANCEL, SHARE, RETRY, DOWNLOAD, IDLE }

    private lateinit var palette: Palette
    private lateinit var prefs: Prefs

    // Structure
    private lateinit var pageView: LinearLayout

    // Download tab
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

    // Settings tab
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

    /** Link already sent to the download. Survives Activity recreation. */
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

        // The app is resizeable and does not declare configChanges: entering split
        // screen, rotating or resizing the window DESTROYS and recreates this Activity. Without
        // this marker, onCreate re-read the link from the intent and fired the download
        // again on every recreation — the "downloading" that showed up when leaving and
        // returning to focus.
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
            // Recreation: repaint the state and only download if the link differs.
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
     * @param isShare true when the link came from the share sheet. It comes
     * as a parameter because `onNewIntent` does NOT update `Activity.intent`: reading the
     * property there returned the action from the first launch and a later
     * share was treated as a pasted link (or the reverse).
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

    // ------------------------------------------------------------- structure

    /**
     * A single page. The Settings tab is gone: the destination folder became a row on the
     * main screen and what used to be share configuration lives in the gear of the
     * quality row.
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

    // ---------------------------------------------------------- Download tab

    private fun buildDownloadPage(): LinearLayout {
        val page = page()

        // HISTORY ----------------------------------------------------------
        historyBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        historyBlock.addView(sectionTitle("HISTORY"))
        historyCard = card()
        // Large fixed square: fits the three history rows with both
        // buttons, and does not change height when the first item arrives.
        // No fixed height: the History takes all the space left between the status
        // and the bottom controls. It scrolls, and items grow BOTTOM-UP
        // (`Gravity.BOTTOM` on the card + reversed order in render): the newest is
        // the closest to the thumb, like the rest of the app.
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

        // Custom ladder: only with Custom selected, and only with the heights
        // the post actually has.
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
        // Super Capture's bar (`timeLogBar`): 42dp field with `bg_field` and
        // an accent button beside it. Here the button is Paste, with glyph and word.
        val linkBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        linkBlock.addView(sectionTitle("LINK"))
        val linkBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(10), 0)
            // No left padding: the field joins the same line as the other
            // blocks. The right side closes on the page margin.
        }
        linkBar.addView(linkField())
        linkBar.addView(pasteButton())
        linkBlock.addView(linkBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // STATUS -----------------------------------------------------------
        // Only the download state: title, bar and the detail line (bytes,
        // compression warning). The instruction "paste a link and choose the
        // quality" is gone from here: the field and the chips say it themselves.
        // THICK status bar: the state centered in the middle of it, the progress
        // bar as background and the byte stats right below.
        val statusCard = FrameLayout(this).apply {
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
            minimumHeight = dp(STATUS_HEIGHT_DP)
        }

        // Background: the progress bar fills the whole block (thick).
        // Custom View, not ProgressBar: see ProgressStrip.kt.
        progressBar = ProgressStrip(this)
        // FIXED height, not MATCH_PARENT: inside a wrap_content FrameLayout the
        // View with no intrinsic size gets an AT_MOST spec and grows to fill
        // the whole screen (the blue bar swallowed the page before this fix).
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
            // Outside the status block: only shows when there is an error to explain.
            visibility = View.GONE
        }
        // Detail inside the block, below the title: bytes downloaded, how much
        // is left, percentage and the cause of an error. It stays hidden when there is
        // nothing to say (Waiting state).
        statusColumn.addView(detailView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) })
        detailView.visibility = View.GONE
        statusCard.addView(statusColumn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL
        ))

        // ACTION (full width, under the thumb) ------------------------------
        // Same design as the Paste button: solid accent, on_accent text. It is the
        // main action of the screen, so it cannot be the secondary outline.
        actionButton = outlinedButton("Download").apply {
            background = palette.roundRect(palette.accent, dp(12).toFloat())
            setTextColor(palette.onAccent)
            setOnClickListener { onAction() }
        }

        // Bottom-up: the link is the first element of the base, the
        // quality rises above it and the history stays at the top.
        // Screen order, top to bottom: STATUS, (breath), HISTORY,
        // QUALITY, LINK, ACTION. The status stays at the top by request; the rest goes down
        // glued to the footer because the spacer with weight 1 eats the free space.
        // The folder is the first thing on the screen: no section title and no
        // description, just the "Choose Download Folder" label and the active template.
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
        // Share + gear side by side, always visible. Share starts
        // disabled (gray) because there is nothing to share; the gear
        // stays here because it configures exactly the share.
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
        // The download button sits right below the link bar: the
        // bar is the last control before the main action.
        return page
    }

    /**
     * The link field, in Super Capture's `timeLogBar` design: 42dp,
     * `bg_field`, 14sp text with 12dp of breathing room, a single line.
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
     * Paste button: solid accent, glyph and word, like Super Capture's `saveButton`
     * (icon + text in a horizontal LinearLayout, never a Button
     * with a compound drawable).
     */
    private fun pasteButton(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = palette.roundRect(palette.accent, dp(12).toFloat())
        isClickable = true
        isFocusable = true
        setPadding(dp(10), 0, dp(10), 0)
        // Fixed, lean width: `WRAP_CONTENT` does not add the text padding on the
        // line, and the button closed before the margin; 120dp was too wide
        // for a two-word target. 88dp = 16 glyph + 5 gap + "Paste".
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

    /** Pastes the first link from the clipboard into the field, without downloading. */
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

    // ---------------------------------------------------------- Settings tab

    private fun openShareQuality() {
        ShareQualityDialog(
            host = this,
            palette = palette,
            prefs = prefs,
            onChanged = { paintChips() }
        ).show()
    }

    // -------------------------------------------------------------- UI state

    private fun prefsLoadState() {
        paintSettings()
        paintChips()
        renderHistory()
    }

    /** The destination row shows the active template (the last used one). */
    private fun paintSettings() {
        if (!::folderValue.isInitialized) return
        val label = prefs.destLabel.ifBlank { Downloader.DEFAULT_LABEL }
        folderValue.text = label
        folderValue.setTextColor(palette.text)
    }

    /**
     * Selected chip = solid accent with dark text; the others outlined.
     * The Custom ladder is cut to what the post offers.
     */
    private fun paintChips() {
        if (!::qualityRow.isInitialized) return
        val selectedPreset = prefs.quality.preset

        // Chips only: the last child of the row is the gear (FrameLayout).
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
     * History: the last three links. Each row shows the link on one line, with
     * an ellipsis at the end; tapping fills the field (does not download, to avoid wasting data
     * by mistake). With no items, the whole block stays hidden.
     */
    /**
     * History: fixed-height square with the last three rows, each one with
     * both buttons (copy the link / share the downloaded file).
     *
     * Empty shows "No History" in place of the rows, but the buttons remain there:
     * the block does not change shape with use.
     */
    private fun renderHistory() {
        if (!::historyCard.isInitialized) return
        // Keeps the visible top so it does not jump when the list was just repainted.
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

            // Square with the first frame: it is the only way to know WHICH video
            // that row is without opening the link.
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

            // The link, clickable: tap and it fills the field (does not download by itself).
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

            // Copy the link.
            row.addView(
                rowIcon(R.drawable.ic_copy) {
                    val clip = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clip?.setPrimaryClip(android.content.ClipData.newPlainText("link", entry.link))

                }
            )

            // Share the already downloaded file.
            val shareIcon = rowIcon(R.drawable.ic_share) {
                shareSingle(Uri.parse(entry.uri))
            }
            if (entry.uri.isBlank()) {
                shareIcon.alpha = 0.35f
                shareIcon.isEnabled = false
            }
            row.addView(shareIcon)

            // Row taller than the default rowSlot (42dp): the 64dp thumbnail
            // needs the space and rowSlot was clipping it.
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

        // The newest item enters at the bottom, so the default view is the END of the list:
        // every new arrival scrolls down by itself and the last one stays visible. Scrolling
        // up is the exception (the user chooses it), and that position is preserved
        // when the render happens for another reason.
        historyScroll.post {
            if (previousTop > 0 && !newItemArrived) {
                // Repaint with nothing new (quality change, return from focus):
                // restores the position the user had chosen.
                historyScroll.scrollTo(0, previousTop)
            } else {
                // New item enters at the bottom: the default view is the end of the list.
                historyScroll.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    /** 32dp icon-button used in the History rows. */
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

    // ---------------------------------------------------------------- states

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

                // Thumbnail of the first frame: it is what identifies the video in
                // the History. Made here (off the UI) and not on every render.
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

    /** Manual mode: the link waits until the user picks the quality. */
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
     * Share always stays in place; what changes is the state. Without a file it stays
     * outlined and gray (isEnabled = false), with a file it becomes solid accent.
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

    /** When another download starts, the previous file no longer applies. */
    private fun hideShareButton() {
        if (!::shareButton.isInitialized) return
        downloaded = ArrayList()
        showShareButton()
    }

    /**
     * Where this download goes. `fromShare` distinguishes a link that arrived via
     * share from one pasted on the screen: only the share respects the override.
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
                // No Content-Length: show what has arrived and no bar.
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
            // Done has nothing to cancel: the footer button goes back to
            // Download, to start another one.
            setAction(Action.IDLE, "Download")
            // Opens the share sheet by itself when the download comes from
            // a share (the user was already sharing something, so the next step
            // is the same gesture). A link pasted on the screen opens nothing:
            // there the share is a choice, and the Share button stays available.
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
     * Replaces the downloaded MP4 with the extracted M4A. The video leaves the disk: the preset is
     * "audio only", keeping both files would fill the folder.
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
        // Duration and short side come from the FILE, not from the post model.
        val probe = Downloader.probe(this, target.uri)
        val seconds = ((probe?.durationMs ?: 0L) / 1000).coerceAtLeast(1)
        // The cache copy exists only for FFmpeg to read; without deleting it, every
        // compression left the original file (tens of MB) in the cache.
        val local = Downloader.localCopy(this, target.uri)
        val result = try {
            Transcode.fitForWhatsApp(
                context = this,
                source = local,
                durationSeconds = seconds
            ) { fraction ->
                // Same bar as the download, now filling while ffmpeg
                // processes: without it the block said "Compressing" with the bar
                // stuck at 100% and nothing indicated progress.
                post {
                    progressBar.progress = fraction
                    // The title already says "Compressing": the line below shows only the
                    // progress, without repeating the word.
                    val percent = (fraction * 100).toInt()
                    // ETA from the MEASURED speed: a long compression took
                    // minutes without any estimate and looked stuck.
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
        // If the "compression" reduced nothing, the original file stays: swapping
        // for a larger one would only waste space and lose quality for free.
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

    // ------------------------------------------------------------ SAF folder

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

    // --------------------------------------------------------- design pieces

    /**
     * Page with the status at the top and the controls glued to the footer.
     *
     * Apps are designed bottom-up, so the link (and the button) sit
     * under the thumb; the status is the requested exception: it stays at the top, always visible,
     * separated from the rest by a spacer with weight. Without the spacer the content rises and
     * the action button does not touch the bottom of the screen.
     */
    private fun page(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(palette.background)
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }

    /**
     * Super Capture section title: 12sp, caps, bold, letterSpacing
     * 0.09, muted, `padding(10, 2, 10, 6)` and 6dp below. It sits LOOSE above the
     * card, never inside it — that is what makes the page read like an app screen and
     * not like a control panel.
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

    /** 10dp between main blocks, Super Capture's ruler. */
    private fun blockParams(topMarginDp: Int = 10): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(topMarginDp) }

    private fun setAction(kind: Action, label: String) {
        action = kind
        // The button is always visible: idle it IS the "Download", which reads the link
        // from the field; in the other states it becomes Cancel / Share Again / Try Again.
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

    /** Writes the detail line and only shows it when there is content. */
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
        /** Status block height: thick bar with the text on top. */
        private const val STATUS_HEIGHT_DP = 54

        private const val STATE_HANDLED_URL = "handled_url"
        private const val STATE_LINK_TEXT = "link_text"

        /** History row height with thumbnail (64dp image + breathing room). */
        private const val HISTORY_ROW_DP = 72
    }
}
