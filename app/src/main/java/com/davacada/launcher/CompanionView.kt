package com.davacada.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * The home-screen companion, drawn from the hand-made character sheet.
 * He walks in through his door when you come home, then wanders, games, works on his laptop,
 * eats, listens to music, scrolls his phone and naps. Tap him and he reacts; tap the floor and he
 * walks over. When a notification arrives while you're on the home screen, he notices it.
 */
class CompanionView(context: Context) : View(context) {

    private enum class Act { IDLE, WALK, GAME, LAPTOP, EAT, PHONE, MUSIC, SLEEP, WAKE, ARRIVE, REACT, NOTICE }

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val zPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCE6ECF5.toInt(); typeface = Typeface.DEFAULT_BOLD; textSize = 15f * resources.displayMetrics.scaledDensity
    }
    private val rng = Random.Default
    private val sprites = HashMap<String, Bitmap>()

    // The sheet draws everything at one scale: a standing pose is ~233 px tall. Show that at 150 dp.
    private val scale get() = 150f * d / 233f
    private val floorY get() = height - 82f * d

    private var x = -1f            // centre of the character
    private var target = -1f
    private var facing = 1f        // sprites face right
    private var act = Act.IDLE
    private var actStart = 0L
    private var actUntil = 0L
    private var lastFrame = 0L
    private var reactPose = "jump"
    private var afterNap = false

    init {
        isClickable = true
        for (name in NAMES) {
            val id = resources.getIdentifier("comp_$name", "drawable", context.packageName)
            if (id != 0) BitmapFactory.decodeResource(resources, id)?.let { sprites[name] = it }
        }
    }

    // ------------------------------------------------------------------ outside events

    /** Walks in through his door from the right edge. */
    fun arrive() {
        if (width == 0) { post { arrive() }; return }
        x = width - 40f * d
        target = width * (0.3f + rng.nextFloat() * 0.25f)
        start(Act.ARRIVE, 900)
    }

    fun depart() { target = -200f; start(Act.WALK, 8000) }

    /** A notification arrived while the home screen is showing. */
    fun notice() { if (act != Act.SLEEP) { target = x; start(Act.NOTICE, 2200) } else { start(Act.WAKE, 2400) } }

    // ------------------------------------------------------------------ behaviour

    private fun start(a: Act, durationMs: Long) {
        act = a
        actStart = SystemClock.uptimeMillis()
        actUntil = actStart + durationMs
        invalidate()
    }

    private fun pickNext() {
        val r = rng.nextFloat()
        when {
            afterNap -> { afterNap = false; start(Act.WAKE, 2600) }
            r < 0.30f -> {
                target = (60f * d + rng.nextFloat() * (width - 220f * d)).coerceAtLeast(60f * d)
                start(Act.WALK, 9000)
            }
            r < 0.45f -> start(Act.GAME, rng.nextLong(9000, 16000))
            r < 0.57f -> start(Act.LAPTOP, rng.nextLong(9000, 15000))
            r < 0.67f -> start(Act.EAT, 8000)
            r < 0.76f -> start(Act.PHONE, rng.nextLong(6000, 10000))
            r < 0.84f -> start(Act.MUSIC, rng.nextLong(8000, 13000))
            r < 0.90f -> { afterNap = true; start(Act.SLEEP, rng.nextLong(14000, 24000)) }
            else -> start(Act.IDLE, rng.nextLong(5000, 9000))
        }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0 || sprites.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        if (x < 0) { x = width * 0.45f; target = x; start(Act.IDLE, 4000) }
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame).coerceIn(0, 50)) / 1000f
        lastFrame = now
        val t = (now - actStart) / 1000f

        // walking happens during WALK and the tail of ARRIVE
        val moving = (act == Act.WALK || (act == Act.ARRIVE && t > 0.7f)) && abs(target - x) > 2f
        if (moving) {
            facing = if (target > x) 1f else -1f
            x += (target - x).coerceIn(-72f * d * dt, 72f * d * dt)
        }
        if (act == Act.WALK && !moving) start(Act.IDLE, rng.nextLong(1500, 3500))
        if (act == Act.ARRIVE && t > 0.7f && !moving) start(Act.IDLE, 2500)
        if (now > actUntil && act != Act.WALK && act != Act.ARRIVE) pickNext()

        var bob = 0f
        var tilt = 0f
        var flip = facing
        val pose: String = when (act) {
            Act.WALK -> frame(WALK, t, 9f)
            Act.ARRIVE -> if (t < 0.7f) "door_7" else frame(WALK, t, 9f)
            Act.IDLE -> { bob = sin(t * 2.2f) * 0.6f * d; if ((t % 9f) > 6.5f) "idle_phone" else frame(IDLE, t, 0.6f) }
            Act.GAME -> { flip = 1f; frame(GAME, t, 2.2f) }
            Act.LAPTOP -> { flip = 1f; frame(LAPTOP, t, 0.5f) }
            Act.EAT -> { flip = 1f; when { t < 3f -> "sit_noodles"; t < 5.5f -> "eat_burger"; else -> "drink" } }
            Act.PHONE -> { flip = 1f; if ((t % 7f) < 5f) "sit_phone" else "idle_phone" }
            Act.MUSIC -> { flip = 1f; tilt = sin(t * 3.4f) * 5f; bob = abs(sin(t * 3.4f)) * -3f * d; "music_1" }
            Act.SLEEP -> { flip = 1f; "sleep_0" }
            Act.WAKE -> { flip = 1f; if (t < 1.4f) "stretch" else "rub_eyes" }
            Act.REACT -> { if (reactPose == "jump") bob = -sin((t / 1.2f).coerceIn(0f, 1f) * Math.PI.toFloat()) * 26f * d; reactPose }
            Act.NOTICE -> if (t < 1.1f) "notify" else "reach"
        }
        if (act == Act.REACT && now > actUntil) start(Act.IDLE, 2500)

        val bmp = sprites[pose] ?: sprites["idle_0"] ?: return
        val w = bmp.width * scale
        val h = bmp.height * scale
        val bottom = floorY + bob
        val rect = RectF(x - w / 2, bottom - h, x + w / 2, bottom)
        canvas.save()
        if (flip < 0) canvas.scale(-1f, 1f, x, bottom - h / 2)
        if (tilt != 0f) canvas.rotate(tilt, x, bottom)
        if (act == Act.SLEEP) {
            val breathe = 1f + sin(t * 1.6f) * 0.012f
            canvas.scale(1f, breathe, x, bottom)
        }
        canvas.drawBitmap(bmp, null, rect, paint)
        canvas.restore()

        if (act == Act.SLEEP) {
            for (i in 0..2) {
                val p = ((t * 0.5f + i / 3f) % 1f)
                zPaint.alpha = (220 * (1f - p)).toInt()
                canvas.drawText("z", rect.left + w * 0.25f + p * 30f * d + i * 6f * d, rect.top - p * 40f * d, zPaint)
            }
        }

        postInvalidateOnAnimation()
    }

    private fun frame(list: Array<String>, t: Float, fps: Float): String = list[((t * fps).toInt()) % list.size]

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        val near = abs(event.x - x) < 70f * d && event.y > floorY - 180f * d && event.y < floorY + 30f * d
        if (near) {
            if (act == Act.SLEEP) { afterNap = false; start(Act.WAKE, 2600); return true }
            reactPose = listOf("jump", "point", "tap").random(rng)
            start(Act.REACT, 1300)
            return true
        }
        if (event.y > floorY - 140f * d) {
            target = event.x.coerceIn(50f * d, width - 50f * d)
            start(Act.WALK, 9000)
            return true
        }
        return false
    }

    companion object {
        private val WALK = arrayOf("walk_0", "walk_1", "walk_2", "walk_3", "walk_4")
        private val IDLE = arrayOf("idle_0", "idle_1", "idle_0", "idle_2", "idle_0", "idle_3")
        private val GAME = arrayOf("sit_game", "game_0", "game_1", "game_2", "game_1", "game_0")
        private val LAPTOP = arrayOf("sit_laptop", "laptop_0", "sit_laptop", "laptop_1")
        private val NAMES = listOf(
            "idle_0", "idle_1", "idle_2", "idle_3", "idle_phone", "walk_0", "walk_1", "walk_2", "walk_3", "walk_4",
            "front_0", "sit_game", "game_0", "game_1", "game_2", "sit_laptop", "laptop_0", "laptop_1",
            "sit_noodles", "eat_burger", "drink", "sit_phone", "music_1", "sleep_0", "stretch", "rub_eyes",
            "notify", "reach", "point", "tap", "jump", "door_7"
        )
    }
}
