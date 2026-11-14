package com.davacada.sanka

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Shader
import android.hardware.fingerprint.FingerprintManager
import android.os.CancellationSignal
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/**
 * The lock screen: matt black with grain, a point over the phone's fingerprint sensor, and ten dots (1 … 9, 0 from
 * top to bottom) at thumb height on the right, wired to the point like a circuit. Tapping a dot enters a digit;
 * when the fingerprint is recognised the circuit lights up from the point out to every dot.
 */
class CircuitLock(ctx: Context) : View(ctx) {
    /** Where the point sits, as a share of the screen's width and height (lined up with the sensor in Settings). */
    var sensorX = 0.5f; var sensorY = 0.832f
    var numbers = true
    var adjusting = false
    var onDigit: ((Char) -> Unit)? = null
    var onPoint: (() -> Unit)? = null
    var onMoved: ((Float, Float) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val grain: Paint = Paint().apply {
        val n = 160
        val px = IntArray(n * n)
        val r = Random(42)
        for (i in px.indices) {
            val v = r.nextFloat()
            px[i] = if (v > 0.5f) Color.argb((22 + r.nextInt(30)), 255, 255, 255) else Color.argb(r.nextInt(40), 0, 0, 0)
        }
        val b = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        shader = BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val trace = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.2f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = 0x1FFFFFFF }
    private val litTrace = Paint(trace).apply { strokeWidth = dp(1.6f) }
    private val litGlow = Paint(trace).apply { strokeWidth = dp(5f); alpha = 50; maskFilter = BlurMaskFilter(dp(3f), BlurMaskFilter.Blur.NORMAL) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(dp(8f), BlurMaskFilter.Blur.NORMAL) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(11.5f); color = 0x4DFFFFFF }
    private val via = Paint(Paint.ANTI_ALIAS_FLAG)

    private val paths = Array(10) { Path() }
    private val measures = arrayOfNulls<PathMeasure>(10)
    private val part = Path()
    private val dotY = FloatArray(10)
    private var dotX = 0f; private var px = 0f; private var py = 0f

    private var lit = 0f            // 0 = dark, 1 = every line lit
    private var pressed = -1; private var pressAlpha = 0f
    private var red = 0f            // wrong PIN / finger not recognised
    private var shake = 0f

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) } // the blur glows need it

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { layoutCircuit() }

    fun layoutCircuit() {
        if (width == 0) return
        px = width * sensorX; py = height * sensorY
        dotX = width - dp(56f)
        val gap = dp(34f)
        val bottom = py - dp(24f)
        for (i in 0 until 10) dotY[i] = bottom - (9 - i) * gap
        val left = px + dp(48f); val right = dotX - dp(14f)
        val c = dp(5f)
        for (i in 0 until 10) {
            val ya = py - dp(36f) + i * dp(8.6f)
            val xi = left + (right - left) * i / 9f
            val yi = dotY[i]
            paths[i].reset()
            paths[i].moveTo(px, py)
            paths[i].lineTo(px + dp(34f), ya)
            paths[i].lineTo(xi - c, ya)
            paths[i].lineTo(xi, ya - c)
            paths[i].lineTo(xi, yi + c)
            paths[i].lineTo(xi + c, yi)
            paths[i].lineTo(dotX - dp(9f), yi)
            measures[i] = PathMeasure(paths[i], false)
        }
        val g = LinearGradient(px, py, dotX, dotY[0], 0xFF7FE0FF.toInt(), 0xFFC9A2FF.toInt(), Shader.TileMode.CLAMP)
        litTrace.shader = g; litGlow.shader = g
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF050505.toInt())
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), grain)
        c.save(); c.translate(shake, 0f)
        for (i in 0 until 10) {
            c.drawPath(paths[i], trace)
            if (lit > 0f) {
                val m = measures[i] ?: continue
                part.reset(); m.getSegment(0f, m.length * lit, part, true)
                c.drawPath(part, litGlow); c.drawPath(part, litTrace)
            }
        }
        val r = dp(5f)
        for (i in 0 until 10) {
            val reached = lit > 0f && (measures[i]?.let { lit >= 0.98f } ?: false)
            val y = dotY[i]
            var col = if (reached) 0xFFBFF3FF.toInt() else 0xCCFFFFFF.toInt()
            var rad = r
            if (i == pressed && pressAlpha > 0f) { col = blend(col, 0xFFC9A2FF.toInt(), pressAlpha); rad = r * (1f + 0.4f * pressAlpha) }
            if (red > 0f) col = blend(col, 0xFFFF5A5A.toInt(), red)
            glow.color = col; glow.alpha = if (reached || i == pressed) 150 else 50
            c.drawCircle(dotX, y, rad * 2f, glow)
            dot.color = col; c.drawCircle(dotX, y, rad, dot)
            if (numbers) c.drawText(((i + 1) % 10).toString(), dotX + dp(16f), y + dp(4f), label)
        }
        // the point: plain, a touch brighter when the circuit lights
        dot.color = when {
            red > 0f -> blend(0xFFF1E6FF.toInt(), 0xFFFF5A5A.toInt(), red)
            lit > 0f -> 0xFFFFFFFF.toInt()
            else -> 0xFFF1E6FF.toInt()
        }
        c.drawCircle(px, py, dp(if (lit > 0f) 9f else 7f), dot)
        if (adjusting) {
            via.style = Paint.Style.STROKE; via.strokeWidth = dp(1.5f); via.color = 0x99FFFFFF.toInt()
            c.drawCircle(px, py, dp(28f), via)
        }
        c.restore()
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = ((a shr s and 255) * (1 - t) + (b shr s and 255) * t).toInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    private var dragging = false
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (adjusting) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    sensorX = (e.x / width).coerceIn(0.1f, 0.9f); sensorY = (e.y / height).coerceIn(0.45f, 0.97f)
                    layoutCircuit()
                }
                MotionEvent.ACTION_UP -> onMoved?.invoke(sensorX, sensorY)
            }
            return true
        }
        if (e.actionMasked != MotionEvent.ACTION_DOWN) return true
        if (lit > 0f) return true
        if (hypot(e.x - px, e.y - py) < dp(36f)) { onPoint?.invoke(); return true }
        if (abs(e.x - dotX) < dp(36f)) {
            val i = (0 until 10).minByOrNull { abs(dotY[it] - e.y) } ?: return true
            if (abs(dotY[i] - e.y) <= dp(17f)) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                flash(i)
                onDigit?.invoke(((i + 1) % 10).digitToChar())
            }
        }
        return true
    }

    private var flashAnim: ValueAnimator? = null
    private fun flash(i: Int) {
        flashAnim?.cancel()
        pressed = i
        flashAnim = ValueAnimator.ofFloat(1f, 0f).setDuration(320).apply {
            addUpdateListener { pressAlpha = it.animatedValue as Float; invalidate() }; start()
        }
    }

    /** Recognised: light the circuit from the point out to every dot, then [done]. */
    fun light(done: () -> Unit) {
        ValueAnimator.ofFloat(0f, 1f).setDuration(520).apply {
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { lit = it.animatedValue as Float; invalidate() }
            start()
        }
        postDelayed(done, 640)
    }

    /** Wrong PIN or finger: everything blinks red and shakes. */
    fun wrong() {
        performHapticFeedback(HapticFeedbackConstants.REJECT)
        ValueAnimator.ofFloat(0f, 1f).setDuration(420).apply {
            addUpdateListener {
                val t = it.animatedValue as Float
                red = if (t < 0.5f) t * 2 else (1 - t) * 2
                shake = (Math.sin(t * Math.PI * 6) * dp(10f) * (1 - t)).toFloat()
                invalidate()
            }
            start()
        }
    }

    /** Finger not recognised: only the point blinks. */
    fun pointMiss() {
        ValueAnimator.ofFloat(1f, 0f).setDuration(380).apply {
            addUpdateListener { red = (it.animatedValue as Float) * 0.8f; invalidate() }; start()
        }
    }
}

/**
 * Listens for the fingerprint without a pop-up window: the phone shows only its own small sensor icon over the
 * sensor, so the lock screen stays visible. Falls back to the system window when this isn't available.
 */
class FingerListener(private val ctx: Context) {
    private var cancel: CancellationSignal? = null
    @Suppress("DEPRECATION")
    private val fm: FingerprintManager? = try { ctx.getSystemService(FingerprintManager::class.java) } catch (e: Exception) { null }

    @Suppress("DEPRECATION")
    val usable: Boolean get() = try { fm != null && fm.isHardwareDetected && fm.hasEnrolledFingerprints() } catch (e: SecurityException) { false }

    @Suppress("DEPRECATION")
    fun start(ok: () -> Unit, miss: () -> Unit, stopped: (String) -> Unit): Boolean {
        stop()
        val m = fm ?: return false
        val c = CancellationSignal()
        cancel = c
        return try {
            m.authenticate(null, c, 0, object : FingerprintManager.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: FingerprintManager.AuthenticationResult?) { cancel = null; ok() }
                override fun onAuthenticationFailed() { miss() }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    if (cancel === c) cancel = null
                    if (errorCode != FingerprintManager.FINGERPRINT_ERROR_CANCELED && errorCode != FingerprintManager.FINGERPRINT_ERROR_USER_CANCELED) stopped(errString?.toString() ?: "")
                }
            }, null)
            true
        } catch (e: Exception) { cancel = null; false }
    }

    fun stop() { cancel?.cancel(); cancel = null }
    val listening get() = cancel != null
}
