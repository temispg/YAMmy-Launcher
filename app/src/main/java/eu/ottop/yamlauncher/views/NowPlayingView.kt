package eu.ottop.yamlauncher.views

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import kotlin.math.abs
import eu.ottop.yamlauncher.R
import eu.ottop.yamlauncher.tasks.NowPlayingManager.NowPlayingInfo

/**
 * Home screen "Now Playing" row: album art, track info and transport controls.
 *
 * Horizontal drags are handled here as swipe-to-dismiss. Every other touch that
 * did not land on a control is mirrored to [homeGestureForwarder] so the usual
 * home gestures (swipe up for the app menu, long press, double tap) keep working
 * over the widget.
 */
class NowPlayingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun onPlayPause()
        fun onSkipToNext()
        fun onSkipToPrevious()
        fun onDismiss()
        fun onOpen()
    }

    var listener: Listener? = null
    var homeGestureForwarder: ((MotionEvent) -> Unit)? = null

    private lateinit var artView: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var previousButton: ImageView
    private lateinit var playPauseButton: ImageView
    private lateinit var nextButton: ImageView

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var isDragging = false
    private var forwardGesture = false
    private var isAnimatingOut = false
    private var isPlaying = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        artView = findViewById(R.id.nowPlayingArt)
        titleView = findViewById(R.id.nowPlayingTitle)
        artistView = findViewById(R.id.nowPlayingArtist)
        previousButton = findViewById(R.id.nowPlayingPrevious)
        playPauseButton = findViewById(R.id.nowPlayingPlayPause)
        nextButton = findViewById(R.id.nowPlayingNext)

        artView.clipToOutline = true
        // Marquee only runs while the view is selected, and only when the title overflows.
        titleView.isSelected = true
        previousButton.setOnClickListener { listener?.onSkipToPrevious() }
        playPauseButton.setOnClickListener { listener?.onPlayPause() }
        nextButton.setOnClickListener { listener?.onSkipToNext() }

        // Swiping is not discoverable by accessibility services; expose dismiss as an action.
        ViewCompat.replaceAccessibilityAction(
            this,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_DISMISS,
            null
        ) { _, _ ->
            listener?.onDismiss()
            true
        }
        ViewCompat.replaceAccessibilityAction(
            this,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            null
        ) { _, _ ->
            listener?.onOpen()
            true
        }
    }

    fun bind(info: NowPlayingInfo) {
        if (isAnimatingOut) return
        titleView.text = info.title
        artistView.text = info.artist
        artistView.visibility = if (info.artist.isNullOrBlank()) GONE else VISIBLE

        if (info.albumArt != null) {
            artView.imageTintList = null
            artView.setImageBitmap(info.albumArt)
        } else {
            artView.imageTintList = ColorStateList.valueOf(titleView.currentTextColor)
            artView.setImageResource(R.drawable.music_note_24px)
        }

        isPlaying = info.isPlaying
        playPauseButton.setImageResource(if (isPlaying) R.drawable.pause_24px else R.drawable.play_arrow_24px)
        playPauseButton.contentDescription =
            context.getString(if (isPlaying) R.string.now_playing_pause else R.string.now_playing_play)

        setControlEnabled(playPauseButton, info.canPlayPause)
        setControlEnabled(nextButton, info.canSkipToNext)
        setControlEnabled(previousButton, info.canSkipToPrevious)
    }

    /** Drops the bitmap reference so nothing is retained while hidden. */
    fun clear() {
        artView.setImageDrawable(null)
        releasePress()
        translationX = 0f
        alpha = 1f
        isAnimatingOut = false
    }

    /**
     * Applies the launcher's text color to the controls and lets [styleText]
     * handle each TextView (color, shadow) the same way as the rest of the home screen.
     */
    fun applyTheme(color: Int, styleText: (TextView) -> Unit) {
        styleText(titleView)
        styleText(artistView)
        val tint = ColorStateList.valueOf(color)
        previousButton.imageTintList = tint
        playPauseButton.imageTintList = tint
        nextButton.imageTintList = tint
        if (artView.imageTintList != null) artView.imageTintList = tint
    }

    private fun setControlEnabled(button: View, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    // ------------------------------------------------------------------
    // Touch handling
    // ------------------------------------------------------------------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            forwardGesture = !isOnControl(ev.x, ev.y)
        }
        if (forwardGesture && !isDragging) {
            homeGestureForwarder?.invoke(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginTracking(ev)
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                if (shouldStartDrag(ev)) {
                    startDrag()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endTracking()
        }
        return false
    }

    // Taps on the row are detected by the forwarded gesture detector (so double tap still
    // works); accessibility gets the same open/dismiss actions via ACTION_CLICK/ACTION_DISMISS.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isAnimatingOut) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginTracking(event)
                isPressed = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (!isDragging && shouldStartDrag(event)) startDrag()
                if (isDragging) {
                    val dx = event.x - downX
                    translationX = dx
                    alpha = (1f - abs(dx) / width.coerceAtLeast(1)).coerceIn(MIN_DRAG_ALPHA, 1f)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) finishDrag(event) else endTracking()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) settleBack()
                endTracking()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun beginTracking(ev: MotionEvent) {
        downX = ev.x
        downY = ev.y
        isDragging = false
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
    }

    private fun endTracking() {
        velocityTracker?.recycle()
        velocityTracker = null
        isDragging = false
        releasePress()
    }

    // setPressed(false) always propagates to children, which would cancel a held control's click.
    private fun releasePress() {
        if (isPressed) isPressed = false
    }

    private fun shouldStartDrag(ev: MotionEvent): Boolean {
        val dx = ev.x - downX
        val dy = ev.y - downY
        return abs(dx) > touchSlop && abs(dx) > abs(dy) * HORIZONTAL_BIAS
    }

    private fun startDrag() {
        isDragging = true
        releasePress()
        parent?.requestDisallowInterceptTouchEvent(true)
        if (forwardGesture) {
            val cancel = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            homeGestureForwarder?.invoke(cancel)
            cancel.recycle()
        }
    }

    private fun finishDrag(event: MotionEvent) {
        val tracker = velocityTracker
        tracker?.addMovement(event)
        tracker?.computeCurrentVelocity(1000)
        val velocityX = tracker?.xVelocity ?: 0f
        val dx = event.x - downX
        endTracking()

        val farEnough = abs(dx) > width * DISMISS_FRACTION
        val flungSameDirection = abs(velocityX) > minFlingVelocity && (velocityX > 0) == (dx > 0)
        if (farEnough || flungSameDirection) {
            dismissTowards(if (dx >= 0) width.toFloat() else -width.toFloat())
        } else {
            settleBack()
        }
    }

    private fun settleBack() {
        animate().translationX(0f).alpha(1f).setDuration(SETTLE_DURATION_MS).start()
    }

    private fun dismissTowards(targetX: Float) {
        isAnimatingOut = true
        animate()
            .translationX(targetX)
            .alpha(0f)
            .setDuration(DISMISS_DURATION_MS)
            .withEndAction {
                isAnimatingOut = false
                listener?.onDismiss()
                translationX = 0f
                alpha = 1f
            }
            .start()
    }

    private fun isOnControl(x: Float, y: Float): Boolean {
        return listOf(previousButton, playPauseButton, nextButton).any { control ->
            control.isVisible &&
                x >= control.left && x <= control.right &&
                y >= control.top && y <= control.bottom
        }
    }

    private companion object {
        const val DISABLED_ALPHA = 0.35f
        const val MIN_DRAG_ALPHA = 0.15f
        const val HORIZONTAL_BIAS = 1.5f
        const val DISMISS_FRACTION = 0.35f
        const val SETTLE_DURATION_MS = 150L
        const val DISMISS_DURATION_MS = 180L
    }
}
