package eu.ottop.yamlauncher.views

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.util.SizeF
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * Horizontally paged stack of app widgets for the home screen.
 *
 * Built on RecyclerView + PagerSnapHelper: a page swipe is a plain view
 * translation, so it stays inside the frame budget, and only the visible page
 * plus one cached neighbour is attached at a time. Each widget's host view is
 * created once and reused, so swiping never re-inflates RemoteViews.
 * Page dots float over the widget on a pill and are only shown while paging,
 * fading out shortly after the stack settles, so they take no permanent space.
 *
 * Touch policy:
 * - Taps go straight to the widget. Paging only starts once a horizontal drag
 *   passes touch slop, at which point RecyclerView cancels the widget's press.
 * - Every touch is mirrored to [homeGestureForwarder] so swipe-up, long press
 *   and double tap keep working over widgets. Vertical drags (and horizontal
 *   ones when there is nothing to page) are taken away from the widget so its
 *   own click does not fire on top of the home gesture, unless the touch
 *   started on a vertically scrollable child (a list widget), which keeps it.
 */
class WidgetStackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    var homeGestureForwarder: ((MotionEvent) -> Unit)? = null

    var dotColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    val pageCount: Int get() = pages.size

    private var hostViews = LinkedHashMap<Int, AppWidgetHostView>()
    private var pages: List<AppWidgetHostView> = emptyList()
    private val widgetContext = WidgetContext(context)
    private var currentPage = 0

    private val density = resources.displayMetrics.density
    private val dotRadius = DOT_RADIUS_DP * density
    private val dotSpacing = DOT_SPACING_DP * density
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillRect = RectF()
    private val pillHalfHeight = dotRadius + PILL_PADDING_DP * density
    private val indicatorMargin = INDICATOR_MARGIN_DP * density

    private var indicatorAlpha = 0f
    private val indicatorAnimator = ValueAnimator().apply {
        addUpdateListener {
            indicatorAlpha = it.animatedValue as Float
            invalidate()
        }
    }
    private val hideIndicator = Runnable { fadeIndicator(0f, INDICATOR_FADE_OUT_MS) }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var forwardGesture = false
    /** Set once a drag was taken from the widget and belongs to the home gesture detector only. */
    private var ownsGesture = false

    init {
        layoutManager = LinearLayoutManager(context, HORIZONTAL, false)
        adapter = PageAdapter()
        itemAnimator = null
        setHasFixedSize(true)
        PagerSnapHelper().attachToRecyclerView(this)
    }

    /**
     * Shows [ids] in order. Host views are cached per id, so re-applying the
     * same list (e.g. after a height change) inflates nothing. Ids that were
     * dropped while a [host] is present are released from the system as well.
     * Passing a null host detaches everything without deleting any widget.
     */
    @SuppressLint("NotifyDataSetChanged")
    fun setWidgets(host: AppWidgetHost?, ids: List<Int>) {
        val manager = AppWidgetManager.getInstance(context)
        val kept = LinkedHashMap<Int, AppWidgetHostView>()
        if (host != null) {
            for (id in ids) {
                val view = hostViews[id]
                    ?: manager.getAppWidgetInfo(id)?.let { host.createView(widgetContext, id, it) }
                    ?: continue
                kept[id] = view
            }
            // Only ids the user removed; an id whose provider is momentarily missing
            // (app updating) stays bound. Idempotent on the system side and also
            // drops the view from the host's own map.
            hostViews.keys.filterNot { it in ids }.forEach { host.deleteAppWidgetId(it) }
        }
        // ponytail: with a null host the old views stay registered inside the
        // AppWidgetHost (no public unregister) until the activity dies or the
        // stack is re-enabled, which recreates them under the same ids.
        hostViews = kept
        pages = kept.values.toList()
        currentPage = currentPage.coerceIn(0, maxOf(0, pages.size - 1))
        adapter?.notifyDataSetChanged()
        pushWidgetSize()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pushWidgetSize()
    }

    /** Tells providers the real page size so they pick the matching layout. */
    private fun pushWidgetSize() {
        val widthDp = (width - paddingLeft - paddingRight) / density
        val heightDp = (height - paddingTop - paddingBottom) / density
        if (widthDp <= 0f || heightDp <= 0f) return
        for (view in pages) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                view.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp, heightDp)))
            } else {
                @Suppress("DEPRECATION")
                view.updateAppWidgetSize(null, widthDp.toInt(), heightDp.toInt(), widthDp.toInt(), heightDp.toInt())
            }
        }
    }

    // ------------------------------------------------------------------
    // Page indicator
    // ------------------------------------------------------------------

    override fun onScrolled(dx: Int, dy: Int) {
        super.onScrolled(dx, dy)
        val pageWidth = width - paddingLeft - paddingRight
        if (pageWidth <= 0) return
        val page = ((computeHorizontalScrollOffset() + pageWidth / 2) / pageWidth)
            .coerceIn(0, maxOf(0, pages.size - 1))
        if (page != currentPage) {
            currentPage = page
            invalidate()
        }
    }

    override fun onScrollStateChanged(state: Int) {
        super.onScrollStateChanged(state)
        removeCallbacks(hideIndicator)
        if (state == SCROLL_STATE_IDLE) {
            postDelayed(hideIndicator, INDICATOR_HIDE_DELAY_MS)
        } else {
            fadeIndicator(1f, INDICATOR_FADE_IN_MS)
        }
    }

    private fun fadeIndicator(target: Float, duration: Long) {
        indicatorAnimator.cancel()
        indicatorAnimator.setFloatValues(indicatorAlpha, target)
        indicatorAnimator.duration = duration
        indicatorAnimator.start()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideIndicator)
        indicatorAnimator.cancel()
        indicatorAlpha = 0f
        super.onDetachedFromWindow()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val count = pages.size
        if (count < 2 || indicatorAlpha <= 0f) return
        val firstX = width / 2f - (count - 1) * dotSpacing / 2f
        val lastX = firstX + (count - 1) * dotSpacing
        val cy = height - indicatorMargin - pillHalfHeight

        // Contrast against the dots, which follow the launcher text color.
        val pillColor = if (ColorUtils.calculateLuminance(dotColor) > 0.5) Color.BLACK else Color.WHITE
        pillPaint.color = ColorUtils.setAlphaComponent(pillColor, (PILL_ALPHA * indicatorAlpha).toInt())
        pillRect.set(firstX - pillHalfHeight, cy - pillHalfHeight, lastX + pillHalfHeight, cy + pillHalfHeight)
        canvas.drawRoundRect(pillRect, pillHalfHeight, pillHalfHeight, pillPaint)

        var cx = firstX
        for (i in 0 until count) {
            dotPaint.color = dotColor
            val alpha = if (i == currentPage) dotPaint.alpha else dotPaint.alpha * INACTIVE_DOT_ALPHA_PERCENT / 100
            dotPaint.alpha = (alpha * indicatorAlpha).toInt()
            canvas.drawCircle(cx, cy, dotRadius, dotPaint)
            cx += dotSpacing
        }
    }

    // ------------------------------------------------------------------
    // Touch handling
    // ------------------------------------------------------------------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = ev.x
            downY = ev.y
            ownsGesture = false
            forwardGesture = scrollState == SCROLL_STATE_IDLE && !hasScrollableChildAt(this, ev.x, ev.y)
        }
        if (forwardGesture) {
            if (scrollState == SCROLL_STATE_IDLE) homeGestureForwarder?.invoke(ev) else cancelForward()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_MOVE && forwardGesture) {
            val dx = abs(e.x - downX)
            val dy = abs(e.y - downY)
            val vertical = dy > touchSlop && dy > dx * VERTICAL_BIAS
            val unpageable = pages.size < 2 && dx > touchSlop
            if (vertical || unpageable) {
                ownsGesture = true
                return true
            }
        }
        return super.onInterceptTouchEvent(e)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // The mirrored stream already reaches the home gesture detector; keep
        // RecyclerView from turning the same drag into a page scroll.
        return ownsGesture || super.onTouchEvent(e)
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // A child inside a widget took over the drag; the gesture belongs to it now.
        if (disallowIntercept) cancelForward()
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    private fun cancelForward() {
        if (!forwardGesture) return
        forwardGesture = false
        val cancel = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        homeGestureForwarder?.invoke(cancel)
        cancel.recycle()
    }

    private fun hasScrollableChildAt(parent: ViewGroup, x: Float, y: Float): Boolean {
        for (i in parent.childCount - 1 downTo 0) {
            val child = parent.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            val cx = x + parent.scrollX - child.left - child.translationX
            val cy = y + parent.scrollY - child.top - child.translationY
            if (cx < 0 || cy < 0 || cx >= child.width || cy >= child.height) continue
            if (child.canScrollVertically(1) || child.canScrollVertically(-1)) return true
            if (child is ViewGroup && hasScrollableChildAt(child, cx, cy)) return true
        }
        return false
    }

    // ------------------------------------------------------------------
    // Adapter
    // ------------------------------------------------------------------

    /**
     * RemoteViews inflate through `LayoutInflater.from(hostView.context)`. For an
     * AppCompat activity that inflater carries the AppCompat factory, which swaps a
     * widget's TextView for a MaterialTextView and reads AppCompat attrs against
     * the remote package's theme, failing with "Couldn't add widget". Hand out a
     * clean inflater instead while keeping the activity for everything else
     * (theme, click PendingIntents, window).
     */
    private class WidgetContext(base: Context) : ContextWrapper(base) {
        private val inflater by lazy { LayoutInflater.from(applicationContext).cloneInContext(this) }

        override fun getSystemService(name: String): Any? =
            if (name == LAYOUT_INFLATER_SERVICE) inflater else super.getSystemService(name)
    }

    private class PageHolder(container: FrameLayout) : ViewHolder(container)

    private inner class PageAdapter : Adapter<PageHolder>() {
        override fun getItemCount() = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PageHolder(
            FrameLayout(parent.context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            }
        )

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val widget = pages[position]
            val container = holder.itemView as FrameLayout
            if (widget.parent === container) return
            (widget.parent as? ViewGroup)?.removeView(widget)
            container.removeAllViews()
            container.addView(widget, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }

        override fun onViewRecycled(holder: PageHolder) {
            (holder.itemView as FrameLayout).removeAllViews()
        }
    }

    companion object {
        /** Identifies this launcher's widgets to the system; must never change once shipped. */
        const val HOST_ID = 0x59414D

        private const val DOT_RADIUS_DP = 3f
        private const val DOT_SPACING_DP = 12f
        private const val INACTIVE_DOT_ALPHA_PERCENT = 40
        private const val PILL_PADDING_DP = 5f
        private const val PILL_ALPHA = 200
        private const val INDICATOR_MARGIN_DP = 12f
        private const val INDICATOR_FADE_IN_MS = 150L
        private const val INDICATOR_FADE_OUT_MS = 300L
        private const val INDICATOR_HIDE_DELAY_MS = 500L
        private const val VERTICAL_BIAS = 1.5f
        private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
