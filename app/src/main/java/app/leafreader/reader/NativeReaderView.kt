package app.leafreader.reader

import android.content.Context
import android.os.Build
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.RoundedCorner
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.findViewTreeLifecycleOwner
import app.leafreader.data.ReaderSettings
import app.leafreader.data.ChapterGraphic

data class Paper(val background: Int, val ink: Int, val muted: Int)
val papers = listOf(
    Paper(0xFFF3EBDD.toInt(), 0xFF302A24.toInt(), 0xFF82776B.toInt()),
    Paper(0xFFFAFAF7.toInt(), 0xFF292C2A.toInt(), 0xFF7D827D.toInt()),
    Paper(0xFFE0EADC.toInt(), 0xFF2C382E.toInt(), 0xFF74816F.toInt()),
    Paper(0xFF202522.toInt(), 0xFFBFC5BA.toInt(), 0xFF7D897E.toInt()),
    Paper(0xFFDAC8A7.toInt(), 0xFF38291D.toInt(), 0xFF51442F.toInt())
)

/** Native Android text layout; no HTML/WebView or bitmap page rendering. */
class NativeReaderView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var onBoundary: (Int) -> Unit = {}
    var onMenu: () -> Unit = {}
    var onPosition: (Int) -> Unit = {}
    var onVisualPosition: (Int, String?) -> Unit = { offset, _ -> onPosition(offset) }
    var onPage: (Int, Int) -> Unit = { _, _ -> }
    private val density = resources.displayMetrics.density
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val chrome = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rasterPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val rasterBounds = android.graphics.RectF()
    private val footer = ReaderFooter()
    internal var footerPlacement: FooterPlacement? = null; private set
    private var document = ""
    private var graphics = emptyList<ChapterGraphic>()
    private var graphicAnchor: String? = null
    private var imageOnly = false
    private data class VisualPage(val data: ChapterLayout? = null, val textPage: TextPage? = null,
        val base: Int = 0, val graphic: ChapterGraphic? = null) {
        val start: Int get() = graphic?.offset ?: (base + textPage!!.start)
        val end: Int get() = graphic?.offset ?: (base + textPage!!.end)
    }
    private var visualPages = emptyList<VisualPage>()
    private var label = ""
    private var sourceNote = "BookHub · 本机进度"
    private var revision = -1
    private var settings = ReaderSettings()
    private var anchor = 0
    private var chapterLayout: ChapterLayout? = null
    private var pageIndex = 0
    private var chapterIndex = -1
    private var chapterCount = 0
    private var chaptersComplete = false
    private var chapterLengths: List<Int>? = null
    private var systemStatus = ReadingStatusSnapshot("—", "—")
    private var statusProvider: ReadingStatusProvider? = null
    private var geometry: ReaderGeometry? = null
    internal val currentGeometry: ReaderGeometry? get() = geometry
    internal val currentLayout: ChapterLayout? get() = chapterLayout
    internal val currentGraphicId: String? get() = graphicAnchor
    internal val visualTextRanges: List<IntRange> get() = visualPages.filter { it.graphic==null }.map { it.start until it.end }
    internal var layoutPasses: Int = 0; private set
    private fun sp(value: Float) = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    val currentOffset: Int get() = anchor
    val currentPage: Int get() = pageIndex
    val pageCount: Int get() = visualPages.size
    internal val displayedProgress: ReadingProgressSnapshot get() {
        if(visualPages.isEmpty() || geometry==null)return ReadingProgressSnapshot(null,null)
        val length=if(imageOnly)pageCount else document.length+graphics.size
        val end=if(pageIndex==visualPages.lastIndex)length else if(imageOnly)pageIndex+1
            else (visualPages.getOrNull(pageIndex)?.end?:0)+visualPages.take(pageIndex+1).count{it.graphic!=null}
        return ReadingProgress.calculate(end,length,chapterIndex,chapterCount,chaptersComplete,chapterLengths)
    }
    internal val compactProgressLabel:String get() {
        val page=if(visualPages.isNotEmpty() && geometry!=null)"${pageIndex+1}/$pageCount" else "—/—"
        return "$page · ${displayedProgress.bookPercent?.let{"$it%"}?:"—"}"
    }

    init {
        isFocusable = true
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun bind(text: String, title: String, offset: Int, version: Int, config: ReaderSettings, provenance: String = "BookHub · 本机进度",
        images: List<ChapterGraphic> = emptyList(), imageId: String? = null, onlyImages: Boolean = false) {
        val changed = text != document || version != revision || images != graphics
        val safe = config.normalized()
        val reflow = changed || !safe.sameLayoutAs(settings)
        if (changed) {
            document = text
            anchor = offset.coerceIn(0, (text.length - 1).coerceAtLeast(0))
            revision = version
            graphics=images;graphicAnchor=imageId;imageOnly=onlyImages
        }
        this.label = title
        sourceNote = provenance
        settings = safe
        paint.color = papers[settings.palette].ink
        if (reflow) rebuild() else invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { rebuild() }

    fun updateProgressContext(index: Int, count: Int, complete: Boolean, lengths: List<Int>?) {
        if (index == chapterIndex && count == chapterCount && complete == chaptersComplete && lengths == chapterLengths) return
        chapterIndex = index; chapterCount = count; chaptersComplete = complete; chapterLengths = lengths?.toList()
        reportPage(); invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        statusProvider?.close()
        statusProvider = findViewTreeLifecycleOwner()?.lifecycle?.let { lifecycle ->
            ReadingStatusProvider(context, lifecycle, onChanged = ::updateSystemStatus)
        }
    }
    override fun onDetachedFromWindow() {
        statusProvider?.close(); statusProvider = null
        super.onDetachedFromWindow()
    }
    internal fun updateSystemStatus(value: ReadingStatusSnapshot) { systemStatus = value; updateDescription(); invalidate() }

    private fun rebuild() {
        if (width <= 0 || height <= 0 || document.isEmpty()) {
            visualPages=emptyList();chapterLayout=null;geometry=null;footerPlacement=null;pageIndex=0;reportPage();invalidate();return
        }
        val area = ReaderGeometry.calculate(width, height, density, sp(settings.fontSp), settings, minOf(sp(11f), 14 * density))
        geometry = area
        paint.textSize = area.fontPx
        paint.color = papers[settings.palette].ink
        paint.typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL)
        chapterLayout = NativePaginator.layout(document, paint, area.textWidth, area.textHeight, settings.lineSpacing, area.paragraphGapPx)
        if(graphics.isEmpty()) visualPages=chapterLayout!!.map.pages.map { VisualPage(chapterLayout,it) }
        else {
            val pages=mutableListOf<VisualPage>();var start=0
            fun textPages(end: Int) {
                if(end>start && !imageOnly && document.substring(start,end).isNotBlank()) {
                    val layout=NativePaginator.layout(document.substring(start,end),paint,area.textWidth,area.textHeight,settings.lineSpacing,area.paragraphGapPx,start==0)
                    pages += layout.map.pages.map { VisualPage(layout,it,start) }
                }
                start=end
            }
            for(graphic in graphics.sortedBy { it.offset }) {
                textPages(graphic.offset.coerceIn(start,document.length));pages += VisualPage(graphic=graphic)
            }
            textPages(document.length);visualPages=pages
        }
        layoutPasses++
        pageIndex = graphicAnchor?.let { key -> visualPages.indexOfFirst { it.graphic?.id==key }.takeIf { it>=0 } }
            ?: visualPages.indexOfFirst { it.graphic==null && anchor>=it.start && anchor<it.end }.takeIf { it>=0 }
            ?: visualPages.indexOfLast { it.start<=anchor }.coerceAtLeast(0)
        reportPage()
        invalidate()
    }

    private fun reportPage() {
        onPage(if(pageCount>0)pageIndex+1 else 0,pageCount)
        updateDescription()
    }
    private fun updateDescription() {
        contentDescription = visualPages.getOrNull(pageIndex)?.let { p ->
            "$label，第 ${pageIndex + 1} 页，共 $pageCount 页。" + (p.graphic?.description ?: document.substring(p.start, p.end)) +
                "。${systemStatus.timeLabel}，电量${systemStatus.batteryLabel}"
        } ?: label
    }

    fun turn(direction: Int) {
        if(visualPages.isEmpty()) return
        val next = pageIndex + direction
        if (next !in visualPages.indices) { onBoundary(direction); return }
        pageIndex = next
        anchor = visualPages[next].start.coerceIn(0,document.lastIndex.coerceAtLeast(0))
        if(anchor>0 && Character.isLowSurrogate(document[anchor])) anchor--
        graphicAnchor=visualPages[next].graphic?.id
        onVisualPosition(anchor,graphicAnchor)
        reportPage()
        invalidate()
        sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED)
    }

    override fun onDraw(canvas: Canvas) {
        val paper = papers[settings.palette]
        canvas.drawColor(paper.background)
        if (settings.palette == 4) KraftPaperTexture.draw(canvas, resources, width, height)
        val area = geometry ?: return
        chrome.color = paper.muted; chrome.textSize = minOf(sp(11f), 14*density)
        if (area.showChrome) {
            val padding = maxOf(area.left, minOf(maxOf(18*density, roundedRadius(false)+4*density), width*.28f))
            val maxLabel = android.text.TextUtils.ellipsize(label, chrome, (width-2*padding).coerceAtLeast(1f), android.text.TextUtils.TruncateAt.END).toString()
            canvas.drawText(maxLabel, padding, 4*density-chrome.fontMetrics.ascent, chrome)
        }
        val visual=visualPages.getOrNull(pageIndex) ?: return
        if(visual.graphic!=null) {
            val bitmap=visual.graphic.bitmap
            val scale=minOf((area.right-area.left)/bitmap.width,(area.bottom-area.top)/bitmap.height)
            val w=bitmap.width*scale;val h=bitmap.height*scale
            val left=area.left+(area.right-area.left-w)/2;val top=area.top+(area.bottom-area.top-h)/2
            canvas.save();canvas.clipRect(area.left,area.top,area.right,area.bottom)
            rasterBounds.set(left,top,left+w,top+h)
            canvas.drawBitmap(bitmap,null,rasterBounds,rasterPaint);canvas.restore()
        } else {
        val data = visual.data ?: return
        val page = visual.textPage ?: return
        for (line in page.firstContentLine..page.lastContentLine) {
            val glyphHeight = data.lineBottom(line) - data.lineTop(line)
            if (glyphHeight <= 0) continue
            val lineY = area.top + data.lineTop(line) - data.lineTop(page.firstContentLine)
            canvas.save()
            canvas.clipRect(area.left, lineY, area.right, minOf(lineY + glyphHeight, area.bottom))
            canvas.translate(area.left, lineY - data.layout.getLineTop(line))
            data.layout.draw(canvas)
            canvas.restore()
        }
        }
        // The passive status row is always outside the rectangle used to paginate the body.
        val leftText = compactProgressLabel
        footerPlacement = footer.draw(canvas, width, height, area.footerTop, density, minOf(sp(11f),14*density),
            roundedRadius(true), leftText, systemStatus, chrome, paper.ink)
    }

    private fun roundedRadius(bottom: Boolean): Float {
        if (Build.VERSION.SDK_INT < 31) return 0f
        val insets = rootWindowInsets ?: return 0f
        return maxOf(insets.getRoundedCorner(if (bottom) RoundedCorner.POSITION_BOTTOM_LEFT else RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0,
            insets.getRoundedCorner(if (bottom) RoundedCorner.POSITION_BOTTOM_RIGHT else RoundedCorner.POSITION_TOP_RIGHT)?.radius ?: 0).toFloat()
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            when { e.x < width * .28f -> turn(-1); e.x > width * .72f -> turn(1); else -> performClick() }
            return true
        }
    })
    private var downX = 0f
    private var downY = 0f
    @android.annotation.SuppressLint("ClickableViewAccessibility") // GestureDetector calls performClick; scroll actions are exposed separately.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { downX = event.x; downY = event.y }
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val dx = event.x - downX; val dy = event.y - downY
            if (kotlin.math.abs(dx) > 48 * density && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                gestures.onTouchEvent(cancel); cancel.recycle()
                turn(if (dx < 0) 1 else -1)
                return true
            }
        }
        gestures.onTouchEvent(event)
        return true
    }
    override fun performClick(): Boolean { super.performClick(); onMenu(); return true }
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_VOLUME_DOWN -> { turn(1); true }
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_VOLUME_UP -> { turn(-1); true }
        else -> super.onKeyDown(keyCode, event)
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.TextView"
        info.contentDescription = "$contentDescription，${displayedProgress.chapterLabel}，${displayedProgress.bookLabel}，时间 ${systemStatus.timeLabel}，电量 ${systemStatus.batteryLabel}"
        info.isScrollable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
    }
    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { turn(1); true }
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { turn(-1); true }
        else -> super.performAccessibilityAction(action, arguments)
    }
}
