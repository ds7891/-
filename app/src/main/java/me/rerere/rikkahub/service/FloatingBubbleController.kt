package me.rerere.rikkahub.service

import android.animation.ObjectAnimator
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import me.rerere.rikkahub.R
import kotlin.math.roundToInt
import kotlin.random.Random

private const val TAG = "FloatingBubble"

/** 无操作多久后自动贴边（毫秒）。 */
private const val SNAP_DELAY_MS = 3000L

/** 贴边动画时长（毫秒）。 */
private const val SNAP_ANIM_DURATION_MS = 260L

/** Q 版小人：眨眼间隔范围与持续时间。 */
private const val BLINK_MIN_INTERVAL_MS = 2600L
private const val BLINK_MAX_INTERVAL_MS = 5200L
private const val BLINK_DURATION_MS = 150L

/** Q 版小人：偶尔抬手挥手的间隔与持续时间。 */
private const val WAVE_MIN_INTERVAL_MS = 9000L
private const val WAVE_MAX_INTERVAL_MS = 15000L
private const val WAVE_DURATION_MS = 1300L

/** 小人高度（dp）。文字在上，小人在下，整体仍是一个胶囊气泡。 */
private const val MASCOT_HEIGHT_DP = 64

/**
 * 悬浮窗保活气泡：AI 生成期间在屏幕上显示一个胶囊形的"清水正在运行中"，用于提示进程仍在工作。
 *
 * - 可自由拖动；
 * - 3 秒无操作后自动贴到最近的屏幕左/右边缘，并缩进去约 1/3（只露出 2/3）；
 * - 拖动到任意位置后重新计时，同样会在 3 秒后贴边；
 * - 文字下方是一个站立看向用户的 Q 版小人：待机呼吸、随机眨眼、偶尔抬手打招呼。
 */
class FloatingBubbleController(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bubbleView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var snapAnimator: ValueAnimator? = null
    private var mascotView: ImageView? = null
    private var breathAnimator: ObjectAnimator? = null
    private var blinking = false
    private var waving = false

    private val snapRunnable = Runnable { snapToNearestEdge() }

    /** 待机 → 眨眼 → 恢复。 */
    private val blinkRunnable = object : Runnable {
        override fun run() {
            val image = mascotView ?: return
            if (!blinking) {
                blinking = true
                image.setImageResource(if (waving) R.drawable.mascot_wave_blink else R.drawable.mascot_blink)
                mainHandler.postDelayed({
                    blinking = false
                    image.setImageResource(if (waving) R.drawable.mascot_wave else R.drawable.mascot_idle)
                }, BLINK_DURATION_MS)
            }
            mainHandler.postDelayed(this, Random.nextLong(BLINK_MIN_INTERVAL_MS, BLINK_MAX_INTERVAL_MS))
        }
    }

    /** 待机 → 抬手挥手（过程中会穿插眨眼）→ 恢复。 */
    private val waveRunnable = object : Runnable {
        override fun run() {
            val image = mascotView ?: return
            if (!waving) {
                waving = true
                image.setImageResource(if (blinking) R.drawable.mascot_wave_blink else R.drawable.mascot_wave)
                mainHandler.postDelayed({
                    waving = false
                    image.setImageResource(if (blinking) R.drawable.mascot_blink else R.drawable.mascot_idle)
                }, WAVE_DURATION_MS)
            }
            mainHandler.postDelayed(this, Random.nextLong(WAVE_MIN_INTERVAL_MS, WAVE_MAX_INTERVAL_MS))
        }
    }

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
        mainHandler.removeCallbacks(blinkRunnable)
        mainHandler.removeCallbacks(waveRunnable)
        snapAnimator?.cancel()
        snapAnimator = null
        breathAnimator?.cancel()
        breathAnimator = null
        mascotView = null
        blinking = false
        waving = false
        val view = bubbleView ?: return
        bubbleView = null
        layoutParams = null
        runCatching { windowManager.removeView(view) }
            .onFailure { Log.e(TAG, "failed to remove overlay view", it) }
    }

    private fun createBubbleView(): View {
        val density = context.resources.displayMetrics.density
        val paddingH = (10 * density).roundToInt()
        val paddingV = (4 * density).roundToInt()
        val text = TextView(context).apply {
            text = context.getString(R.string.floating_window_running_text)
            textSize = 11f
            setTextColor(0xFF1B4332.toInt())
            gravity = Gravity.CENTER
        }
        val mascot = ImageView(context).apply {
            setImageResource(R.drawable.mascot_idle)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(paddingH, paddingV, paddingH, paddingV)
            addView(text)
            addView(
                mascot,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (MASCOT_HEIGHT_DP * density).roundToInt(),
                ),
            )
        }
        // 先量出实际高度，再按高度的一半做圆角：长方形 + 左右两个半圆（胶囊形）
        container.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        container.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0xFFD8F3DC.toInt())
            setStroke((1f * density).roundToInt(), 0xFF74C69D.toInt())
            cornerRadius = container.measuredHeight / 2f
        }
        mascotView = mascot
        startMascotAnimation(mascot)
        return container
    }

    /** 启动 Q 版小人的待机呼吸 + 随机眨眼 + 偶尔挥手。 */
    private fun startMascotAnimation(image: ImageView) {
        // 以底部为轴轻微缩放，模拟呼吸起伏（等布局完成后再取高度）
        image.post { image.pivotY = image.height.toFloat() }
        breathAnimator = ObjectAnimator.ofFloat(image, "scaleY", 1f, 1.035f).apply {
            duration = 1800L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
        mainHandler.postDelayed(blinkRunnable, Random.nextLong(BLINK_MIN_INTERVAL_MS, BLINK_MAX_INTERVAL_MS))
        mainHandler.postDelayed(waveRunnable, Random.nextLong(WAVE_MIN_INTERVAL_MS, WAVE_MAX_INTERVAL_MS))
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