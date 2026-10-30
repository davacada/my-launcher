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
 * The home-screen companion, drawn from the hand-made character sheets.
 *
 * He comes out of his Red Magic door when you come home, then picks things to do: wander, game,
 * play guitar, put his headphones on and dance, have a burger meal, work on his laptop, scroll his
 * phone, or yawn, grab a pillow and go to bed under his blanket. Tap him and he reacts, a bit less
 * patiently each time; tap him five times and he storms off through the door and comes back later.
 * Tap the floor and he walks over; a notification makes him look up and check his phone.
 */
class CompanionView(context: Context) : View(context) {

    /** One stretch of an animation: [frames] each shown for [frameMs]; loops until [totalMs]. */
    private class Seg(val frames: List<String>, val frameMs: Long, val totalMs: Long = frames.size * frameMs, val flag: Int = 0)

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val zPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCE6ECF5.toInt(); typeface = Typeface.DEFAULT_BOLD; textSize = 15f * resources.displayMetrics.scaledDensity
    }
    private val rng = Random.Default
    private val sprites = HashMap<String, Bitmap?>()

    // Sprites are normalised so a standing pose is 233 px tall; show that at 150 dp.
    private val scale get() = 150f * d / 233f
    private val floorY get() = height - 82f * d
    private val doorRight get() = width - 18f * d

    private var x = -1f
    private var facing = 1f

    // ---- what he's doing: either playing a script in place, or walking somewhere
    private var script: List<Seg> = emptyList()
    private var scriptStart = 0L
    private var scriptLen = 0L
    private var then: (() -> Unit)? = null

    private var walking = false
    private var walkTarget = 0f
    private var walkFrames = WALK
    private var walkSpeed = 72f
    private var afterWalk: (() -> Unit)? = null
    private var walkStart = 0L

    private var away = false           // gone through the door
    private var busyWithDoor = false   // door scenes ignore taps
    private var lastFrame = 0L

    // ---- the door, drawn behind him while he comes out
    private var doorFrames: List<Pair<String, Long>> = emptyList()
    private var doorStart = 0L

    // ---- tap patience
    private var taps = 0
    private var lastTap = 0L

    init { isClickable = true }

    private fun sprite(name: String): Bitmap? = sprites.getOrPut(name) {
        val id = resources.getIdentifier("comp_$name", "drawable", context.packageName)
        if (id == 0) null else BitmapFactory.decodeResource(resources, id)
    }

    // ------------------------------------------------------------------ outside events

    /** Comes out of his door near the right edge. */
    fun arrive() {
        if (width == 0) { post { arrive() }; return }
        away = false; busyWithDoor = true; taps = 0
        val dw = (sprite("door_open")?.width ?: 120) * scale
        x = doorRight - dw * 0.45f
        facing = -1f
        doorFrames = listOf("door_shut" to 450L, "door_closing" to 180L, "door_half" to 180L, "door_open" to 900L,
            "door_half" to 200L, "door_closing" to 200L, "door_shut" to 500L)
        doorStart = SystemClock.uptimeMillis()
        // stand still (invisible) while the door opens, then walk out
        play(listOf(Seg(listOf("_none"), 800))) {
            walkTo(width * (0.3f + rng.nextFloat() * 0.25f), WALK, 72f) { busyWithDoor = false; idle() }
        }
    }

    /** A notification arrived while the home screen is showing. */
    fun notice() {
        if (away || busyWithDoor || width == 0) return
        if (isAsleep()) { wake(); return }
        walking = false
        play(listOf(Seg(listOf("notice"), 1000), Seg(listOf("phone_0", "phone_1", "phone_2"), 700, 2800))) { idle() }
    }

    // ------------------------------------------------------------------ the engine

    private fun play(segs: List<Seg>, next: (() -> Unit)? = null) {
        walking = false
        script = segs
        scriptLen = segs.sumOf { it.totalMs }
        scriptStart = SystemClock.uptimeMillis()
        then = next
        invalidate()
    }

    private fun walkTo(target: Float, frames: List<String>, speedDp: Float, next: (() -> Unit)? = null) {
        walking = true
        walkTarget = target
        walkFrames = frames
        walkSpeed = speedDp
        walkStart = SystemClock.uptimeMillis()
        afterWalk = next
        invalidate()
    }

    /** Current segment and pose of the script at time [t] ms. */
    private fun scriptPose(t: Long): Pair<String, Int> {
        var acc = 0L
        for (s in script) {
            if (t < acc + s.totalMs) {
                val i = (((t - acc) / s.frameMs) % s.frames.size).toInt()
                return s.frames[i] to s.flag
            }
            acc += s.totalMs
        }
        val last = script.lastOrNull() ?: return "idle_0" to 0
        return last.frames.last() to last.flag
    }

    private fun isAsleep(): Boolean {
        if (walking || script.isEmpty()) return false
        return scriptPose(SystemClock.uptimeMillis() - scriptStart).second == SLEEP
    }

    // ------------------------------------------------------------------ behaviour

    private fun r(a: Long, b: Long) = rng.nextLong(a, b)

    private fun idle() {
        val segs = mutableListOf(Seg(listOf("idle_0", "idle_1", "idle_0", "idle_2", "idle_0", "idle_3"), 900, r(3000, 6500)))
        if (rng.nextFloat() < 0.3f) segs += Seg(listOf("phone_0", "phone_1", "phone_2"), 800, r(2500, 4500))
        play(segs) { pickNext() }
    }

    private fun pickNext() {
        val r = rng.nextFloat()
        when {
            r < 0.22f -> walkTo((70f * d + rng.nextFloat() * (width - 200f * d)).coerceAtLeast(70f * d), WALK, 72f) { idle() }
            r < 0.34f -> game()
            r < 0.45f -> guitar()
            r < 0.56f -> dance()
            r < 0.64f -> meal()
            r < 0.72f -> laptop()
            r < 0.79f -> sitPhone()
            r < 0.87f -> bedtime()
            else -> idle()
        }
    }

    private fun game() {
        if (rng.nextBoolean()) {
            val sit = Seg(listOf("g_sit0", "g_sit1", "g_sit2", "g_sit3"), 420, r(3500, 6000))
            val moment = listOf(
                listOf(Seg(listOf("g_win"), 1300)),
                listOf(Seg(listOf("g_lose"), 1600)),
                listOf(Seg(listOf("g_pop"), 1200), Seg(listOf("g_popwin"), 1300)),
                listOf(Seg(listOf("g_legup"), 2200)),
                listOf(Seg(listOf("g_hype", "g_hype2"), 450, 1800)),
                listOf(Seg(listOf("g_heart"), 1500)),
                listOf(Seg(listOf("g_lean"), 1800)),
            )
            val segs = mutableListOf<Seg>()
            repeat(rng.nextInt(2, 4)) { segs += sit; segs += moment.random(rng) }
            segs += sit
            play(segs) { idle() }
        } else {
            play(listOf(
                Seg(listOf("g_s0", "g_s1"), 500, r(2500, 4000)),
                Seg(listOf("g_s2"), 700),
                Seg(listOf("g_c0", "g_c1", "g_c2"), 300, r(2500, 4000)),
                Seg(listOf(if (rng.nextBoolean()) "g_jump" else "g_win"), 1200),
                Seg(listOf("sitgame_0", "sitgame_1"), 600, r(3000, 5000)),
            )) { idle() }
        }
    }

    private fun guitar() {
        val strum = (3..14).map { "gt_%02d".format(it) }
        val segs = mutableListOf(
            Seg(listOf("gt_00"), 1100), Seg(listOf("gt_01"), 1000), Seg(listOf("gt_02"), 800),
            Seg(strum, 380, r(5000, 8000)),
            Seg(listOf("gt_15", "gt_16"), 700, 2100),
            Seg(strum, 380, r(3500, 6000)),
            Seg(listOf("gt_17"), 1100),
        )
        if (rng.nextBoolean()) segs += listOf(Seg(listOf("gt_18", "gt_19"), 900, 3600), Seg(listOf("gt_20"), 1200), Seg(listOf("gt_21"), 1500))
        if (rng.nextBoolean()) segs += Seg(listOf("gt_22", "gt_23"), 900, r(3500, 6000))
        play(segs) { idle() }
    }

    private fun dance() {
        val moves = (0..23).map { "dance_%02d".format(it) }
        play(listOf(
            Seg(listOf("hp_0", "hp_1", "hp_2"), 500),
            Seg(listOf("vibe_0", "vibe_1", "vibe_2", "vibe_3"), 600, 4800, MUSIC),
            Seg(listOf("pick_0", "pick_1", "pick_2"), 650),
            Seg(moves, 330, r(9000, 15000), MUSIC),
            Seg(listOf("dance_end0", "dance_end1"), 700),
        )) { idle() }
    }

    private fun meal() {
        fun m(vararg n: Int) = n.map { "meal_%02d".format(it) }
        play(listOf(
            Seg(m(0, 1), 900), Seg(m(2), 800), Seg(m(3, 4), 800), Seg(m(5), 1100),
            Seg(m(6, 7, 8, 9, 10, 11), 500, 6000),
            Seg(m(12, 13, 14), 700), Seg(m(15, 16), 700),
            Seg(m(17, 18, 19), 650, 3900),
            Seg(m(20, 21, 22, 23), 650, 3900),
            Seg(m(24), 1500), Seg(m(25), 900), Seg(m(26), 1200), Seg(m(27), 1000), Seg(m(28, 29), 700),
        )) { idle() }
    }

    private fun laptop() {
        play(listOf(
            Seg(listOf("laptop_2"), 1500, r(4000, 7000)),
            Seg(listOf("laptop_0", "laptop_1"), 1100, r(4000, 7000)),
            Seg(listOf("think_0"), 2000),
            Seg(listOf("laptop_2"), 1500, r(3000, 6000)),
        )) { idle() }
    }

    private fun sitPhone() = play(listOf(Seg(listOf("sitphone_0", "think_0"), 2600, r(6000, 10000)))) { idle() }

    private fun bedtime() {
        val sleep = Seg(listOf("sleep_0", "sleep_1", "sleep_2", "sleep_3", "sleep_4", "sleep_5"), 1400, r(20000, 40000), SLEEP)
        play(listOf(
            Seg(listOf("yawn_0"), 1100), Seg(listOf("yawn_1"), 1000), Seg(listOf("rub"), 1000),
            Seg(listOf("drowsy_0", "drowsy_1"), 800, 1600),
            Seg(listOf("pillow_0", "pillow_1"), 450, 1800),
            Seg(listOf("hug_0", "hug_1"), 1200, 2400),
            Seg(listOf("bedphone"), 2600),
            Seg(listOf("blanket_0", "blanket_1", "blanket_2", "blanket_3"), 700),
            Seg(listOf("lie_0", "lie_1", "lie_2"), 800),
            sleep,
            Seg(listOf("stir_0", "stir_1"), 900, 1800, SLEEP),
            Seg(listOf("snug_0", "snug_2"), 1300, 2600, SLEEP),
            sleep,
        )) { wake() }
    }

    private fun wake() {
        play(listOf(Seg(listOf("stir_1"), 700), Seg(listOf("yawn_0"), 1200), Seg(listOf("rub"), 1100), Seg(listOf("yawn_1"), 900))) { idle() }
    }

    /** Taps make him a little less patient each time; the fifth sends him off through his door. */
    private fun react() {
        val now = SystemClock.uptimeMillis()
        taps = if (now - lastTap < 8000) taps + 1 else 1
        lastTap = now
        walking = false
        when (taps) {
            1 -> play(listOf(Seg(listOf("t1_a"), 650), Seg(listOf("t1_b"), 650))) { idle() }
            2 -> play(listOf(Seg(listOf("t2_a"), 500), Seg(listOf("t2_b"), 700), Seg(listOf("t2_c"), 800))) { idle() }
            3 -> play(listOf(Seg(listOf("t3_a"), 800), Seg(listOf("t3_b"), 800), Seg(listOf("t3_c"), 700))) { idle() }
            4 -> play(listOf(Seg(listOf("t4_a"), 700), Seg(listOf("t4_b"), 650), Seg(listOf("t4_c"), 700), Seg(listOf("t4_d"), 900))) { idle() }
            else -> { busyWithDoor = true; play(listOf(
                Seg(listOf("t5_a"), 600), Seg(listOf("t5_b"), 1100), Seg(listOf("t5_c"), 700), Seg(listOf("t5_d"), 700),
                Seg(listOf("t5_e"), 600), Seg(listOf("t5_turn"), 500),
            )) { leave() } }
        }
    }

    /** Grabs his backpack, walks to the right edge and goes through the door; back in a while. */
    private fun leave() {
        busyWithDoor = true
        val tw = (sprite("door_touch")?.width ?: 150) * scale
        val stand = doorRight - tw * 0.72f
        walkTo(stand, PACK, 80f) {
            x = stand
            play(listOf(Seg(listOf("door_touch"), 500, flag = DOOR), Seg(listOf("door_in1", "door_in2", "door_in3", "door_in4", "door_in5"), 330, flag = DOOR),
                Seg(listOf("door_open", "door_half", "door_closing"), 300, flag = DOOR), Seg(listOf("door_shut"), 900, flag = DOOR_FADE))) {
                away = true; taps = 0
                play(listOf(Seg(listOf("_none"), r(20000, 40000)))) { arrive() }
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val now = SystemClock.uptimeMillis()
        if (x < 0) { x = width * 0.45f; idle() }
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame).coerceIn(0, 50)) / 1000f
        lastFrame = now

        // the door behind him (when coming out)
        if (doorFrames.isNotEmpty()) {
            val dtm = now - doorStart
            var acc = 0L
            var name: String? = null
            var alpha = 1f
            for ((i, f) in doorFrames.withIndex()) {
                if (dtm < acc + f.second) {
                    name = f.first
                    if (i == 0) alpha = (dtm - acc) / f.second.toFloat()
                    if (i == doorFrames.lastIndex) alpha = 1f - (dtm - acc) / f.second.toFloat()
                    break
                }
                acc += f.second
            }
            if (name == null) doorFrames = emptyList() else drawDoor(canvas, name, alpha)
        }

        var pose: String
        var flag = 0
        var flip = 1f
        if (walking) {
            val dir = if (walkTarget > x) 1f else -1f
            facing = dir
            x += (walkTarget - x).coerceIn(-walkSpeed * d * dt, walkSpeed * d * dt)
            pose = walkFrames[(((now - walkStart) / 115) % walkFrames.size).toInt()]
            flip = facing
            if (abs(walkTarget - x) < 2f) { walking = false; val n = afterWalk; afterWalk = null; n?.invoke() ?: idle() }
        } else {
            val t = now - scriptStart
            val p = scriptPose(t)
            pose = p.first; flag = p.second
            if (t >= scriptLen) { val n = then; then = null; if (n != null) n() else idle() }
        }

        if (flag == DOOR || flag == DOOR_FADE) {
            var a = 1f
            if (flag == DOOR_FADE) {
                var acc = 0L
                for (s in script) { if (s.flag == DOOR_FADE) break; acc += s.totalMs }
                a = (1f - (now - scriptStart - acc) / 900f).coerceIn(0f, 1f)
            }
            drawDoor(canvas, pose, a)
        } else if (pose != "_none" && !away) {
            drawChar(canvas, pose, flag, flip, (now - scriptStart) / 1000f)
        }
        postInvalidateOnAnimation()
    }

    private fun drawDoor(canvas: Canvas, name: String, alpha: Float) {
        val bmp = sprite(name) ?: return
        val w = bmp.width * scale; val h = bmp.height * scale
        paint.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
        canvas.drawBitmap(bmp, null, RectF(doorRight - w, floorY - h, doorRight, floorY), paint)
        paint.alpha = 255
    }

    private fun drawChar(canvas: Canvas, pose: String, flag: Int, flip: Float, t: Float) {
        val bmp = sprite(pose) ?: sprite("idle_0") ?: return
        val w = bmp.width * scale
        val h = bmp.height * scale
        // keep wide poses (guitar, bed) on screen
        val cx = x.coerceIn(w / 2 - 20f * d, width - w / 2 + 20f * d)
        var bob = 0f
        var tilt = 0f
        if (flag == MUSIC) { tilt = sin(t * 3.4f) * 3f; bob = abs(sin(t * 3.4f)) * -3f * d }
        val bottom = floorY + bob
        val rect = RectF(cx - w / 2, bottom - h, cx + w / 2, bottom)
        canvas.save()
        if (flip < 0) canvas.scale(-1f, 1f, cx, bottom - h / 2)
        if (tilt != 0f) canvas.rotate(tilt, cx, bottom)
        if (flag == SLEEP) canvas.scale(1f, 1f + sin(t * 1.6f) * 0.012f, cx, bottom)
        canvas.drawBitmap(bmp, null, rect, paint)
        canvas.restore()

        if (flag == SLEEP) {
            for (i in 0..2) {
                val p = ((t * 0.5f + i / 3f) % 1f)
                zPaint.alpha = (220 * (1f - p)).toInt()
                canvas.drawText("z", cx + w * 0.15f + p * 30f * d + i * 6f * d, rect.top + h * 0.15f - p * 40f * d, zPaint)
            }
        }
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        if (away || busyWithDoor) return false
        val near = abs(event.x - x) < 75f * d && event.y > floorY - 180f * d && event.y < floorY + 30f * d
        if (near) {
            if (isAsleep()) wake() else react()
            return true
        }
        if (event.y > floorY - 140f * d && !isAsleep()) {
            walkTo(event.x.coerceIn(60f * d, width - 60f * d), WALK, 72f) { idle() }
            return true
        }
        return false
    }

    companion object {
        private const val MUSIC = 1
        private const val SLEEP = 2
        private const val DOOR = 3
        private const val DOOR_FADE = 4
        private val WALK = (0..5).map { "walk_$it" }
        private val PACK = (0..9).map { "pack_$it" }
    }
}
