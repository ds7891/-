package me.rerere.rikkahub.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import me.rerere.rikkahub.R
import kotlin.math.roundToInt

private const val TAG = "FloatingBubble"

/** 无操作多久后自动贴边（毫秒）。 */
private const val SNAP_DELAY_MS = 3000L

/** 贴边动画时长（毫秒）。 */
private const val SNAP_ANIM_DURATION_MS = 260L

/**
 * 悬浮窗保活气泡：AI 生成期间在屏幕上显示一个椭圆形的"清水正在运行中"，用于提示进程仍在工作。
 *
 * - 可自由拖动；
 * - 3 秒无操作后自动贴到最近的屏幕左/右边缘，并缩进去约 1/3（只露出 2/3）；
 * - 拖动到任意位置后重新计时，同样会在 3 秒后贴边。
 */
class FloatingBubbleController(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bubbleView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var snapAnimator: ValueAnimator? = null

    private val snapRunnable = Runnable { snapToNearestEdge() }

    /** 显示气泡；没有悬浮窗权限或已经显示时不做任何事。 */
    fun show() {
        if (bubbleView != null) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "skip showing bubble: overlay permission not granted")
            return
        }
        val view = createBubbleView()
        val params = createLayoutParams(view)
        attachTouchListener(view, params)
        val added = runCatching { windowManager.addView(view, params) }.isSuccess
        if (!added) {
            Log.e(TAG, "failed to add overlay view")
            return
        }
        bubbleView = view
        layoutParams = params
        // 刚出现时若 3 秒内没人操作，自动贴到最近的边缘
        scheduleSnap()
    }

    /** 隐藏气泡。 */
    fun hide() {
        mainHandler.removeCallbacks(snapRunnable)
        snapAnimator?.cancel()
        snapAnimator = null
        val view = bubbleView ?: return
        bubbleView = null
        layoutParams = null
        runCatching { windowManager.removeView(view) }
            .onFailure { Log.e(TAG, "failed to remove overlay view", it) }
    }

    private fun createBubbleView(): TextView {
        val density = context.resources.displayMetrics.density
        val paddingH = (18 * density).roundToInt()
        val paddingV = (9 * density).roundToInt()
        return TextView(context).apply {
            text = context.getString(R.string.floating_window_running_text)
            textSize = 13f
            setTextColor(0xFF1B4332.toInt())
            gravity = Gravity.CENTER
            setPadding(paddingH, paddingV, paddingH, paddingV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFD8F3DC.toInt())
                setStroke((1.5f * density).roundToInt(), 0xFF74C69D.toInt())
            }
        }
    }

    private fun createLayoutParams(view: View): WindowManager.LayoutParams {
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val metrics = context.resources.displayMetrics
        // 先测量出气泡尺寸，初始放在右侧、约屏幕 1/3 高度处（完整可见）
        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (metrics.widthPixels - view.measuredWidth).coerceAtLeast(0)
            y = (metrics.heightPixels / 3f).roundToInt()
        }
    }

    private fun attachTouchListener(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    // 开始操作时取消待执行的贴边
                    mainHandler.removeCallbacks(snapRunnable)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).roundToInt()
                    val dy = (event.rawY - downRawY).roundToInt()
                    params.x = startX + dx
                    params.y = startY + dy
                    updateLayout(view, params)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 松手后重新计时，3 秒无操作自动贴边
                    scheduleSnap()
                    true
                }

                else -> false
            }
        }
    }

    private fun scheduleSnap() {
        mainHandler.removeCallbacks(snapRunnable)
        mainHandler.postDelayed(snapRunnable, SNAP_DELAY_MS)
    }

    private fun snapToNearestEdge() {
        val view = bubbleView ?: return
        val params = layoutParams ?: return
        val metrics = context.resources.displayMetrics
        val bubbleWidth = (view.width.takeIf { it > 0 } ?: view.measuredWidth).toFloat()
        val bubbleHeight = (view.height.takeIf { it > 0 } ?: view.measuredHeight).toFloat()
        if (bubbleWidth <= 0f) return

        val centerX = params.x + bubbleWidth / 2f
        // 靠左贴左边缘，靠右贴右边缘；缩进去 1/3，只露出 2/3
        val targetX = if (centerX < metrics.widthPixels / 2f) {
            -bubbleWidth / 3f
        } else {
            metrics.widthPixels - bubbleWidth * 2f / 3f
        }
        val targetY = params.y
            .coerceIn(0, (metrics.heightPixels - bubbleHeight).roundToInt().coerceAtLeast(0))

        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofFloat(params.x.toFloat(), targetX).apply {
            duration = SNAP_ANIM_DURATION_MS
            addUpdateListener { animator ->
                params.x = (animator.animatedValue as Float).roundToInt()
                params.y = targetY
                updateLayout(view, params)
            }
            start()
        }
    }

    private fun updateLayout(view: View, params: WindowManager.LayoutParams) {
        runCatching { windowManager.updateViewLayout(view, params) }
    }
}