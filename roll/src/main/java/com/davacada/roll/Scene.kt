package com.davacada.roll

import kotlin.math.PI

/**
 * What a clip looks like at a moment: where it sits in the frame, and how far into a transition it is.
 * The viewer and the export both draw from this, so they always match.
 */
object Scene {
    class Transition(val id: String, val name: String, val code: Int)

    /** Transitions into the next clip. Most play half at the end of one clip and half at the start of the next. */
    val TRANSITIONS = listOf(
        Transition("none", "Cut", 0),
        Transition("dissolve", "Dissolve", 9),
        Transition("black", "Dip to black", 1),
        Transition("white", "Dip to white", 2),
        Transition("zoom", "Zoom", 3),
        Transition("whip", "Whip pan", 4),
        Transition("slide", "Slide", 10),
        Transition("spin", "Spin", 5),
        Transition("blur", "Blur", 6),
        Transition("burn", "Film burn", 7),
        Transition("glitch", "Glitch", 8),
    )
    fun transition(id: String) = TRANSITIONS.firstOrNull { it.id == id } ?: TRANSITIONS[0]

    /** Everything the shader needs for one frame. */
    class Frame(
        val srcAspect: Float, val outAspect: Float,
        val fit: Boolean, val bg: Int,                  // 0 black, 1 blur, 2 white
        val scale: Float, val ox: Float, val oy: Float, val rotRad: Float, val flip: Boolean,
        val tCode: Int, val tAmount: Float, val tDir: Float,   // dir -1: leaving this clip · +1: arriving
        val prev: Clip?,                                   // dissolve: the clip before (its last frame shows)
    ) {
        /** Packed for the shader: uA, uB, uC, uP1, uP2. */
        fun uniforms(): Array<FloatArray> {
            val p = prev
            return arrayOf(
                floatArrayOf(srcAspect, outAspect, if (fit) 1f else 0f, bg.toFloat()),
                floatArrayOf(scale, ox, oy, rotRad),
                floatArrayOf(if (flip) 1f else 0f, tCode.toFloat(), tAmount, tDir),
                if (p == null) floatArrayOf(1f, 0f, 1f, 0f) else floatArrayOf(p.aspect, if (p.fit) 1f else 0f, p.scale, (p.rot * PI / 180).toFloat()),
                if (p == null) floatArrayOf(0f, 0f, 0f, 0f) else floatArrayOf(p.ox, p.oy, if (p.flip) 1f else 0f, if (tCode == 9 && tDir > 0) tAmount else 0f),
            )
        }
    }

    fun bgCode(c: Clip) = when (c.bg) { "black" -> 0; "white" -> 2; else -> 1 }

    private fun ease(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    /**
     * Clip [i] of [clips], [localMs] into it (edit time), in a frame of [outAspect].
     * [srcAspect] is the picture's real shape if known (the player may tell us), else the clip's.
     */
    fun frame(clips: List<Clip>, i: Int, localMs: Long, outAspect: Float, srcAspect: Float? = null): Frame {
        val c = clips[i]
        val len = c.lengthMs.coerceAtLeast(1)
        var tCode = 0; var amt = 0f; var dir = 0f; var prev: Clip? = null
        // arriving from the clip before
        val before = clips.getOrNull(i - 1)
        if (before != null && before.trans != "none") {
            val t = transition(before.trans); val d = before.transMs.coerceIn(100, 2000)
            if (t.code == 9) { if (localMs < d) { tCode = 9; amt = 1f - ease(localMs / d.toFloat()); dir = 1f; prev = before } }
            else if (localMs < d / 2) { tCode = t.code; amt = 1f - ease(localMs / (d / 2f)); dir = 1f }
        }
        // leaving into the next one
        if (tCode == 0 && i < clips.size - 1 && c.trans != "none") {
            val t = transition(c.trans); val d = c.transMs.coerceIn(100, 2000)
            if (t.code != 9 && localMs > len - d / 2) { tCode = t.code; amt = ease((localMs - (len - d / 2)) / (d / 2f)); dir = -1f }
        }
        val kb = if (c.kenBurns) 1f + 0.14f * (localMs.toFloat() / len).coerceIn(0f, 1f) else 1f
        return Frame(srcAspect ?: c.aspect, outAspect, c.fit, bgCode(c), c.scale * kb, c.ox, c.oy, (c.rot * PI / 180).toFloat(), c.flip, tCode, amt, dir, prev)
    }

    /**
     * The fragment shader's body, shared by the viewer (GLSL 300, video texture) and the export (GLSL 100).
     * The caller defines SRC(uv) and PREV(uv) and the uniforms uA, uB, uC, uP1, uP2, uE (bloom, halation, fringing, sharpen).
     */
    const val BODY = """
vec2 mapUv(vec2 uv, float srcA, float outA, float fit, float sc, vec2 off, float rot, float flip) {
  vec2 p = (uv - 0.5) * vec2(outA, 1.0);
  p -= off * vec2(outA, 1.0);
  float cr = cos(rot); float sr = sin(rot);
  p = vec2(cr * p.x - sr * p.y, sr * p.x + cr * p.y);
  p /= max(sc, 0.01);
  if (flip > 0.5) p.x = -p.x;
  float k = fit > 0.5 ? min(1.0, outA / srcA) : max(1.0, outA / srcA);
  return p / (vec2(srcA, 1.0) * k) + 0.5;
}
bool inside(vec2 t) { return t.x >= 0.0 && t.x <= 1.0 && t.y >= 0.0 && t.y <= 1.0; }
float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }

vec3 background(vec2 uv) {
  if (uA.w < 0.5) return vec3(0.0);
  if (uA.w > 1.5) return vec3(1.0);
  vec2 t = mapUv(uv, uA.x, uA.y, 0.0, 1.12, vec2(0.0), 0.0, uC.x);
  vec3 s = vec3(0.0);
  for (int i = 0; i < 16; i++) {
    float a = float(i) * 2.39996;
    float r = 0.012 + 0.07 * sqrt(float(i) / 16.0);
    s += SRC(clamp(t + vec2(cos(a), sin(a)) * r, 0.0, 1.0)).rgb;
  }
  return s / 16.0 * 0.72;
}

/** Light effects on the picture: bloom, halation (red glow), colour fringing, sharpen. */
vec3 fx(vec2 t, vec3 c) {
  if (uE.x + uE.y + uE.z + uE.w < 0.001) return c;
  vec2 px = vec2(0.0011, 0.0011 * uA.x);
  if (uE.w > 0.001) {
    vec3 b = (SRC(t + vec2(px.x, 0.0)).rgb + SRC(t - vec2(px.x, 0.0)).rgb + SRC(t + vec2(0.0, px.y)).rgb + SRC(t - vec2(0.0, px.y)).rgb) * 0.25;
    c += (c - b) * uE.w * 2.5;
  }
  if (uE.z > 0.001) { vec2 d = (t - 0.5) * uE.z * 0.014; c.r = SRC(clamp(t + d, 0.0, 1.0)).r; c.b = SRC(clamp(t - d, 0.0, 1.0)).b; }
  if (uE.x + uE.y > 0.001) {
    vec3 g = vec3(0.0);
    for (int i = 0; i < 12; i++) {
      float a = float(i) * 2.39996; float r = 0.005 + 0.03 * sqrt(float(i) / 12.0);
      g += max(SRC(clamp(t + vec2(cos(a), sin(a) * uA.x) * r, 0.0, 1.0)).rgb - 0.62, 0.0);
    }
    g /= 12.0;
    c += g * uE.x * 2.4;
    c += vec3(1.0, 0.28, 0.12) * dot(g, vec3(0.333)) * uE.y * 3.2;
  }
  return c;
}

vec3 clipAt(vec2 uv, float sc, vec2 off, float rot) {
  vec2 t = mapUv(uv, uA.x, uA.y, uA.z, sc, off, rot, uC.x);
  if (!inside(t)) return background(uv);
  return fx(t, SRC(t).rgb);
}

vec3 frameColor(vec2 uv) {
  float code = uC.y; float a = uC.z; float dir = uC.w;
  float sc = uB.x; vec2 off = uB.yz; float rot = uB.w;
  vec3 c;
  if (code > 2.5 && code < 3.5) {                 // zoom
    c = clipAt(uv, sc * (1.0 + a * 0.7), off, rot);
    c = mix(c, vec3(1.0), a * a * 0.35);
  } else if (code > 3.5 && code < 4.5) {          // whip pan: slides away with motion blur
    vec2 o = off + vec2(dir < 0.0 ? -a : a, 0.0);
    c = vec3(0.0);
    for (int i = 0; i < 10; i++) c += clipAt(uv + vec2((float(i) / 9.0 - 0.5) * a * 0.25, 0.0), sc, o, rot);
    c /= 10.0;
  } else if (code > 9.5 && code < 10.5) {         // slide
    c = clipAt(uv, sc, off + vec2(dir < 0.0 ? -a : a, 0.0), rot);
  } else if (code > 4.5 && code < 5.5) {          // spin
    c = clipAt(uv, sc * (1.0 + a * 0.5), off, rot + dir * a * 1.3);
  } else if (code > 5.5 && code < 6.5) {          // blur
    c = vec3(0.0);
    for (int i = 0; i < 12; i++) { float an = float(i) * 2.39996; c += clipAt(uv + vec2(cos(an), sin(an)) * a * 0.035 * sqrt(float(i) / 12.0), sc, off, rot); }
    c = c / 12.0 + a * 0.08;
  } else if (code > 7.5 && code < 8.5) {          // glitch
    float row = floor(uv.y * 24.0);
    float j = hash(vec2(row, floor(a * 9.0))) > 0.72 ? (hash(vec2(row, 3.0)) - 0.5) * a * 0.12 : 0.0;
    vec2 u2 = uv + vec2(j, 0.0);
    c = vec3(clipAt(u2 + vec2(a * 0.025, 0.0), sc, off, rot).r, clipAt(u2, sc, off, rot).g, clipAt(u2 - vec2(a * 0.025, 0.0), sc, off, rot).b);
  } else {
    c = clipAt(uv, sc, off, rot);
  }
  if (code > 0.5 && code < 1.5) c = mix(c, vec3(0.0), a);                         // dip to black
  if (code > 1.5 && code < 2.5) c = mix(c, vec3(1.0), a);                         // dip to white
  if (code > 6.5 && code < 7.5) {                                                 // film burn
    float g = 0.55 + 0.45 * sin(uv.x * 5.0 + uv.y * 3.0 + a * 4.0);
    c += vec3(1.0, 0.5, 0.15) * a * g * 1.2;
    c = mix(c, vec3(1.0, 0.88, 0.66), a * a * 0.7);
  }
  if (uP2.w > 0.001) {                                                            // dissolve from the last frame before
    vec2 t = mapUv(uv, uP1.x, uA.y, uP1.y, uP1.z, uP2.xy, uP1.w, uP2.z);
    vec3 p = inside(t) ? PREV(t).rgb : vec3(0.0);
    c = mix(c, p, uP2.w);
  }
  return clamp(c, 0.0, 1.0);
}
"""
}
