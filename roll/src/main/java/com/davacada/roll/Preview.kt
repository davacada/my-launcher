package com.davacada.roll

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The editor's viewer. The player draws into a texture; this draws that texture to the screen through the
 * grade (a 3D colour table), the overlay, vignette and grain, in one pass on the GPU. Every change shows on
 * the very next frame, paused or playing.
 */
class GlPreview(ctx: Context, private val lutDir: File) : GLSurfaceView(ctx), GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    /** Gets the surface the player should draw into (again after the screen comes back). */
    var onSurface: ((Surface) -> Unit)? = null
    /** Surfaces for the overlay players (V2, V3). */
    var onLayerSurface: ((Int, Surface) -> Unit)? = null
    /** V2 / V3 now: aspect, fit, scale, rotation (rad), x, y, flip, opacity — or null when nothing shows. */
    @Volatile var layers = arrayOfNulls<FloatArray>(2)
        set(v) { field = v; requestRender() }
    private val layerTex = IntArray(2); private val layerSt = arrayOfNulls<SurfaceTexture>(2); private val layerSurf = arrayOfNulls<Surface>(2)
    private val layerMat = Array(2) { FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) } }
    /** Show the picture without the grade (while the preview is held). */
    var bypass = false
        set(v) { field = v; requestRender() }

    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private var st: SurfaceTexture? = null
    private var surface: Surface? = null
    private var oesTex = 0; private var lutTex = 0; private var ovTex = 0
    private var prog = 0
    private val stMatrix = FloatArray(16)
    private var viewW = 1; private var viewH = 1
    private var videoRatio = 16f / 9f
    private var viewRatio = 16f / 9f

    // grade state (set on the main thread, read on the GL thread)
    @Volatile private var lutData: ByteBuffer? = null
    @Volatile private var lutDirty = false
    @Volatile private var useLut = false
    @Volatile private var grain = 0f
    @Volatile private var vignette = 0f
    @Volatile private var ovAlpha = 0f
    @Volatile private var ovFrames: List<Bitmap> = emptyList()
    @Volatile private var ovFps = 0f
    @Volatile private var ovKind = "none"
    private var ovUploaded: Bitmap? = null
    private var lastGrade = ""

    init {
        setEGLContextClientVersion(3)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setRatios(video: Float, view: Float) { videoRatio = video; viewRatio = view; requestRender() }

    /** How the current clip sits in the frame and where it is in a transition (set every tick by the editor). */
    @Volatile var frame: Scene.Frame? = null
        set(v) { field = v; requestRender() }
    /** Dissolve: the last frame of the clip before. */
    @Volatile var prevBitmap: Bitmap? = null
        set(v) { if (v !== field) { field = v; prevDirty = true; requestRender() } }
    /** Titles and captions, drawn over everything at the viewer's size (premultiplied). */
    @Volatile var textBitmap: Bitmap? = null
        set(v) { field = v; textDirty = true; requestRender() }
    /** Bloom, halation, fringing, sharpen (from the grade). */
    @Volatile var hideVideo = false
        set(v) { field = v; requestRender() }
    @Volatile var fx = FloatArray(4)
        set(v) { field = v; requestRender() }
    @Volatile private var prevDirty = false
    @Volatile private var textDirty = false
    private var prevTex = 0; private var textTex = 0

    /** New grade: rebuilds the colour table and overlay in the background, then redraws. */
    private var current: Grade? = null
    private var ovSize = ""
    fun setGrade(g: Grade) {
        current = g
        val key = g.json().toString() + viewW + "x" + viewH
        if (key == lastGrade) return
        lastGrade = key
        val copy = Grade().apply { copyFrom(g) }
        work.execute {
            val use = !copy.neutral
            val buf = if (use) {
                val n = Looks.SIZE
                val cube = Looks.lut(copy, lutDir)
                val bb = ByteBuffer.allocateDirect(n * n * n * 3).order(ByteOrder.nativeOrder())
                for (b in 0 until n) for (gg in 0 until n) for (r in 0 until n) {
                    val c = cube[r][gg][b]
                    bb.put((c shr 16 and 255).toByte()); bb.put((c shr 8 and 255).toByte()); bb.put((c and 255).toByte())
                }
                bb.position(0); bb
            } else null
            val kind = copy.overlay
            val size = "${viewW}x$viewH"
            val frames = if (kind != ovKind || ovFrames.isEmpty() || size != ovSize) {
                val k = Overlays.byId(kind)
                if (k == null) emptyList() else {
                    val w = maxOf(2, viewW / 2); val h = maxOf(2, viewH / 2)
                    (0 until k.frames).map { Overlays.draw(kind, w, h, it) }
                }
            } else ovFrames
            main.post {
                lutData = buf; useLut = use; lutDirty = true
                grain = Looks.grainOf(copy); vignette = Looks.vignetteOf(copy)
                ovKind = kind; ovFrames = frames; ovSize = size; ovFps = Overlays.byId(kind)?.fps ?: 0f
                ovAlpha = if (Overlays.byId(kind) != null) copy.overlayAmount else 0f
                ovUploaded = null
                requestRender()
            }
        }
    }

    fun release() { work.shutdownNow(); queueEvent { st?.release(); surface?.release(); for (k in 0..1) { layerSt[k]?.release(); layerSurf[k]?.release() } } }

    // ---------------------------------------------------------------- GL

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val t = IntArray(5); GLES30.glGenTextures(5, t, 0)
        oesTex = t[0]; lutTex = t[1]; ovTex = t[2]; prevTex = t[3]; textTex = t[4]
        for (x in intArrayOf(prevTex, textTex)) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, x)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }
        prevDirty = true; textDirty = true
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        for (p in intArrayOf(GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_TEXTURE_MAG_FILTER)) GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES30.GL_LINEAR)
        for (p in intArrayOf(GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R)) GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ovTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glGenTextures(2, layerTex, 0)
        for (k in 0..1) {
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, layerTex[k])
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            val lt = SurfaceTexture(layerTex[k]); lt.setOnFrameAvailableListener(this)
            layerSt[k]?.release(); layerSt[k] = lt
            val ls = Surface(lt); layerSurf[k]?.release(); layerSurf[k] = ls
            main.post { onLayerSurface?.invoke(k, ls) }
        }
        prog = program(VERT, FRAG)
        lutDirty = true; ovUploaded = null
        val tex = SurfaceTexture(oesTex)
        tex.setOnFrameAvailableListener(this)
        st?.release(); st = tex
        val s = Surface(tex); surface?.release(); surface = s
        main.post { onSurface?.invoke(s) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width; viewH = height
        GLES30.glViewport(0, 0, width, height)
        main.post { lastGrade = ""; current?.let { setGrade(it) } }   // overlays are drawn at the viewer's size
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) { requestRender() }

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }

    override fun onDrawFrame(gl: GL10?) {
        val tex = st ?: return
        try { tex.updateTexImage() } catch (e: Exception) { return }
        tex.getTransformMatrix(stMatrix)
        for (k in 0..1) layerSt[k]?.let { lt -> try { lt.updateTexImage(); lt.getTransformMatrix(layerMat[k]) } catch (e: Exception) {} }
        if (lutDirty) {
            lutDirty = false
            lutData?.let { d ->
                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
                GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8, Looks.SIZE, Looks.SIZE, Looks.SIZE, 0, GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, d)
            }
        }
        val secs = tex.timestamp / 1e9
        val frames = ovFrames
        val ovFrame = if (frames.isEmpty()) null else frames[if (ovFps <= 0f || frames.size == 1) 0 else ((secs * ovFps).toLong() % frames.size).toInt()]
        if (ovFrame != null && ovFrame !== ovUploaded && !ovFrame.isRecycled) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ovTex)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, ovFrame, 0)
            ovUploaded = ovFrame
        }
        if (prevDirty) { prevDirty = false; prevBitmap?.takeIf { !it.isRecycled }?.let { GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, prevTex); GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, it, 0) } }
        val tb = textBitmap
        if (textDirty) { textDirty = false; tb?.takeIf { !it.isRecycled }?.let { GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textTex); GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, it, 0) } }
        GLES30.glClearColor(0f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(prog)
        val aPos = GLES30.glGetAttribLocation(prog, "aPos"); val aUv = GLES30.glGetAttribLocation(prog, "aUv")
        quad.position(0); GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 16, quad); GLES30.glEnableVertexAttribArray(aPos)
        quad.position(2); GLES30.glVertexAttribPointer(aUv, 2, GLES30.GL_FLOAT, false, 16, quad); GLES30.glEnableVertexAttribArray(aUv)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(prog, "uSt"), 1, false, stMatrix, 0)
        val f = frame ?: Scene.Frame(videoRatio, viewRatio, false, 0, 1f, 0f, 0f, 0f, false, 0, 0f, 0f, null)
        val u = f.uniforms()
        for ((k, name) in listOf("uA", "uB", "uC", "uP1", "uP2").withIndex()) GLES30.glUniform4fv(GLES30.glGetUniformLocation(prog, name), 1, u[k], 0)
        if (prevBitmap == null) GLES30.glUniform4f(GLES30.glGetUniformLocation(prog, "uP2"), 0f, 0f, 0f, 0f)
        val e = if (bypass) FloatArray(4) else fx
        GLES30.glUniform4f(GLES30.glGetUniformLocation(prog, "uE"), e[0], e[1], e[2], e[3])
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uHide"), if (hideVideo) 1f else 0f)
        val ls = layers
        for (k in 0..1) {
            val v = ls.getOrNull(k)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE5 + k); GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, layerTex[k])
            GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uL$k"), 5 + k)
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(prog, "uL${k}M"), 1, false, layerMat[k], 0)
            GLES30.glUniform4f(GLES30.glGetUniformLocation(prog, "uL${k}A"), v?.get(0) ?: 1f, v?.get(1) ?: 0f, v?.get(2) ?: 1f, v?.get(3) ?: 0f)
            GLES30.glUniform4f(GLES30.glGetUniformLocation(prog, "uL${k}B"), v?.get(4) ?: 0f, v?.get(5) ?: 0f, v?.get(6) ?: 0f, if (v == null) 0f else v[7])
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, prevTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uPrev"), 3)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE4); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uText"), 4)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uTextA"), if (tb != null) 1f else 0f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uTex"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uLut"), 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ovTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uOv"), 2)
        val on = !bypass
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uUseLut"), if (on && useLut && lutData != null) 1f else 0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uLutScale"), (Looks.SIZE - 1f) / Looks.SIZE)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uLutOffset"), 0.5f / Looks.SIZE)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uGrain"), if (on) grain * 0.14f else 0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uVig"), if (on) vignette else 0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uOvA"), if (on && ovFrame != null) ovAlpha * Overlays.breath(ovKind, secs) else 0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uSeed"), ((secs * 24).toLong() % 997).toFloat())
        GLES30.glUniform2f(GLES30.glGetUniformLocation(prog, "uSize"), viewW.toFloat(), viewH.toFloat())
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type); GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
        val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("Roll", "shader: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    private fun program(v: String, f: String): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, v)); GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, f))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("Roll", "link: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    companion object {
        private const val VERT = """#version 300 es
in vec2 aPos;
in vec2 aUv;
out vec2 vUv;
void main() {
  gl_Position = vec4(aPos, 0.0, 1.0);
  vUv = aUv;
}
"""
        private val FRAG = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex;
uniform mat4 uSt;
uniform sampler2D uPrev;
uniform sampler2D uText;
uniform float uTextA;
uniform highp sampler3D uLut;
uniform sampler2D uOv;
uniform float uUseLut;
uniform float uLutScale;
uniform float uLutOffset;
uniform float uGrain;
uniform float uVig;
uniform float uOvA;
uniform float uSeed;
uniform vec2 uSize;
uniform vec4 uA; uniform vec4 uB; uniform vec4 uC; uniform vec4 uP1; uniform vec4 uP2; uniform vec4 uE;
uniform float uHide;
uniform samplerExternalOES uL0; uniform samplerExternalOES uL1;
uniform mat4 uL0M; uniform mat4 uL1M;
uniform vec4 uL0A; uniform vec4 uL0B; uniform vec4 uL1A; uniform vec4 uL1B;
in vec2 vUv;
out vec4 outColor;
#define SRC(t) texture(uTex, (uSt * vec4((t), 0.0, 1.0)).xy)
#define PREV(t) texture(uPrev, vec2((t).x, 1.0 - (t).y))
""" + Scene.BODY + """
/** An overlay (V2 / V3) laid over [c]: placed like a clip, but with nothing around it. */
vec3 layer(vec3 c, vec2 uv, samplerExternalOES s, mat4 m, vec4 A, vec4 B) {
  if (B.w < 0.001) return c;
  vec2 t = mapUv(uv, A.x, uA.y, A.y, A.z, B.xy, A.w, B.z);
  if (!inside(t)) return c;
  return mix(c, texture(s, (m * vec4(t, 0.0, 1.0)).xy).rgb, B.w);
}
float rand(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233)) + uSeed * 0.731) * 43758.5453); }
void main() {
  vec3 c = uHide > 0.5 ? vec3(0.0) : frameColor(vUv);
  c = layer(c, vUv, uL0, uL0M, uL0A, uL0B);
  c = layer(c, vUv, uL1, uL1M, uL1A, uL1B);
  if (uUseLut > 0.5) c = texture(uLut, c * uLutScale + uLutOffset).rgb;
  if (uOvA > 0.001) { vec4 o = texture(uOv, vec2(vUv.x, 1.0 - vUv.y)); c = mix(c, o.rgb / max(o.a, 0.001), o.a * uOvA); }
  vec2 d = vUv - 0.5;
  c *= 1.0 - uVig * smoothstep(0.15, 0.75, dot(d, d) * 2.0);
  if (uGrain > 0.0001) {
    vec2 cell = floor(vUv * uSize / 2.0);
    float n = rand(cell) + rand(cell + vec2(17.0, 31.0)) - 1.0;
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c += n * uGrain * (1.0 - abs(l - 0.5) * 1.3);
  }
  if (uTextA > 0.5) { vec4 t = texture(uText, vec2(vUv.x, 1.0 - vUv.y)); c = c * (1.0 - t.a) + t.rgb; }
  outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
    }
}
