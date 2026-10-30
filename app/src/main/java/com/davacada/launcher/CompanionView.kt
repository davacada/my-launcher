package com.davacada.launcher

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.*
import kotlin.random.Random

/** Procedurally articulated 2D companion (drawn in code, no images). All limbs animate independently. */
class CompanionView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val rng = Random.Default
    private val density = resources.displayMetrics.density
    private val size = 196f * density
    private var x = -1f
    private var target = -1f
    private var action = 0 // idle, walk, sit, sleep, game, eat
    private var nextAction = 0L
    private var last = 0L
    private var step = 0f
    private var doorSince = 0L
    private var arriving = false
    private var departing = false
    private var facing = 1f
    private val floorY get() = height - 82f * density
    private val bodyWidth get() = size * .38f

    init { isClickable = true }

    fun arrive() {
        arriving = true; departing = false; doorSince = SystemClock.uptimeMillis()
        x = width.toFloat() - bodyWidth * .45f
        target = (width * .48f).coerceAtLeast(bodyWidth)
        action = 1; nextAction = doorSince + 6000; invalidate()
    }
    fun depart() {
        departing = true; arriving = false; doorSince = SystemClock.uptimeMillis()
        target = -bodyWidth; action = 1; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val now = SystemClock.uptimeMillis()
        if (x < 0 && last == 0L) { x = width * .46f; target = x }
        val dt = if (last == 0L) 0f else ((now - last).coerceIn(0, 50)) / 1000f
        last = now
        if (!departing && now > nextAction && abs(x - target) < 3f) {
            action = rng.nextInt(6)
            if (action == 1) target = (bodyWidth + rng.nextFloat() * (width - bodyWidth * 2).coerceAtLeast(1f))
            nextAction = now + rng.nextLong(4000, 10000)
        }
        val moving = abs(target - x) > 2f
        if (moving) {
            facing = if (target > x) 1f else -1f
            x += (target - x).coerceIn(-145f * density * dt, 145f * density * dt)
            step += dt * 10f
        }
        val doorVisible = doorSince != 0L && now - doorSince < 2300L
        if (doorVisible) drawDoor(canvas, if (arriving) width.toFloat() else 0f, floorY, now - doorSince)
        if (!(departing && x <= -bodyWidth)) drawPerson(canvas, now, moving)
        if (arriving && now - doorSince > 2300) { arriving = false; doorSince = 0 }
        if (departing && x <= -bodyWidth) return
        postInvalidateDelayed(16)
    }

    private fun line(c: Canvas, color: Int, stroke: Float, vararg pts: Float) {
        p.color = color; p.strokeWidth = stroke; p.style = Paint.Style.STROKE
        val path = Path().apply { moveTo(pts[0], pts[1]); var i = 2; while (i < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 } }
        c.drawPath(path, p); p.style = Paint.Style.FILL
    }
    private fun oval(c: Canvas, color: Int, l: Float, t: Float, r: Float, b: Float) {
        p.style = Paint.Style.FILL; p.color = color; c.drawOval(l, t, r, b, p)
    }
    private fun rect(c: Canvas, color: Int, l: Float, t: Float, r: Float, b: Float, radius: Float = 0f) {
        p.style = Paint.Style.FILL; p.color = color; c.drawRoundRect(l,t,r,b,radius,radius,p)
    }
    private fun drawPerson(c: Canvas, now: Long, moving: Boolean) {
        val scale = size / 200f
        c.save(); c.translate(x, floorY); c.scale(scale * facing, scale)
        val phase = if (moving) sin(step) else 0f
        val breath = sin(now / 680.0).toFloat() * 1.5f
        val sit = !moving && (action == 2 || action == 4 || action == 5)
        val sleep = !moving && action == 3
        val skin = Color.rgb(147, 91, 64)
        val shadow = Color.rgb(81, 53, 44)
        val cloth = Color.rgb(37, 41, 49)
        val pants = Color.rgb(76, 76, 68)
        val boots = Color.rgb(176, 123, 66)
        if (sleep) { c.rotate(79f, 0f, -66f); c.translate(0f, 30f) }
        if (sit) c.translate(0f, 44f)
        // Separate legs: hip, knee and ankle change with the walk cycle.
        for (side in intArrayOf(-1, 1)) {
            val stride = if (moving) phase * side * 19f else 0f
            val hipX = side * 13f
            val kneeX = hipX + stride * .65f + if (sit) 23f else 0f
            val kneeY = -48f + if (moving) abs(stride) * .28f else if (sit) 18f else 0f
            val footX = hipX + stride + if (sit) 43f else 0f
            val footY = if (sit) -10f else 0f
            line(c, pants, 20f, hipX, -83f, kneeX, kneeY, footX, footY - 8f)
            line(c, boots, 14f, footX - 4f, footY - 5f, footX + 12f, footY - 5f)
        }
        // Torso and separately animated arms.
        rect(c, cloth, -30f, -142f + breath, 30f, -76f, 16f)
        for (side in intArrayOf(-1, 1)) {
            val swing = if (moving) phase * side * 18f else sin(now / 900.0 + side).toFloat() * 2f
            val shoulder = side * 25f
            val elbowX = shoulder + side * 11f - swing * .65f
            val elbowY = -111f + if (sit && action == 4) -9f else 0f
            val handX = elbowX + side * 5f - swing
            val handY = -83f + if (sit && action == 4) -28f else 0f
            line(c, cloth, 15f, shoulder, -131f, elbowX, elbowY, handX, handY)
            oval(c, skin, handX - 7f, handY - 7f, handX + 7f, handY + 7f)
        }
        // Neck, head, ears, beard, and individual twists.
        rect(c, skin, -10f, -152f + breath, 10f, -133f + breath, 4f)
        oval(c, skin, -28f, -199f + breath, 28f, -143f + breath)
        oval(c, shadow, -25f, -168f + breath, 25f, -142f + breath)
        oval(c, skin, -18f, -172f + breath, 18f, -153f + breath)
        for (i in -3..3) {
            val hx = i * 8f
            line(c, Color.rgb(24, 23, 27), 8f, hx, -192f + breath, hx + sin(i * 1.8).toFloat() * 4f, -201f + (abs(i) % 3) * 4f + breath)
        }
        val blink = (now % 4400L) in 0L..130L
        for (side in intArrayOf(-1, 1)) {
            line(c, Color.rgb(20, 19, 21), 2.7f, side * 7f - 4f, -176f + breath, side * 7f + 3f, -176f + breath)
            if (!blink) oval(c, Color.rgb(20, 19, 21), side * 7f - 1.7f, -176f + breath, side * 7f + 1.7f, -172f + breath)
        }
        line(c, Color.rgb(33, 22, 24), 2.4f, -5f, -157f + breath, 5f, -157f + breath)
        if (sit && action == 4) {
            rect(c, Color.rgb(32, 35, 44), -17f, -109f, 17f, -93f, 5f)
            oval(c, Color.rgb(110, 170, 211), -9f, -103f, -5f, -99f)
            oval(c, Color.rgb(231, 109, 110), 7f, -103f, 11f, -99f)
        }
        if (sit && action == 5) {
            oval(c, Color.rgb(224, 175, 90), 27f, -117f, 43f, -103f)
        }
        if (sleep) {
            p.color = Color.rgb(160, 170, 195); p.textSize = 20f; p.typeface = Typeface.DEFAULT_BOLD
            c.drawText("z", 32f, -205f, p)
        }
        c.restore()
    }
    private fun drawDoor(c: Canvas, edge: Float, ground: Float, age: Long) {
        val w = 43f * density; val h = size * .9f
        val left = if (edge > 0) width - w else 0f
        val opening = (age / 400f).coerceIn(0f, 1f)
        rect(c, Color.rgb(43, 34, 38), left, ground - h, left + w, ground, 5f * density)
        rect(c, Color.rgb(234, 181, 104), left + 5f * density, ground - h + 6f * density,
            left + 5f * density + (w - 10f * density) * opening, ground - 5f * density, 2f * density)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN && abs(event.x - x) < bodyWidth && event.y > floorY - size * 1.2f) {
            action = 1; target = (event.x + (if (event.x < width / 2) bodyWidth else -bodyWidth)).coerceIn(bodyWidth, width - bodyWidth)
            nextAction = SystemClock.uptimeMillis() + 5000; invalidate(); return true
        }
        return false
    }
}
