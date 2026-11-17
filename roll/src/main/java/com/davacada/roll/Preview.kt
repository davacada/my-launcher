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

    fun release() { work.shutdownNow(); queueEvent { st?.release(); surface?.release() } }

    // ---------------------------------------------------------------- GL

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val t = IntArray(3); GLES30.glGenTextures(3, t, 0)
        oesTex = t[0]; lutTex = t[1]; ovTex = t[2]
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
        GLES30.glClearColor(0f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(prog)
        // centre-crop the video into the frame shape
        val sx: Float; val sy: Float
        if (videoRatio > viewRatio) { sx = viewRatio / videoRatio; sy = 1f } else { sx = 1f; sy = videoRatio / viewRatio }
        val aPos = GLES30.glGetAttribLocation(prog, "aPos"); val aUv = GLES30.glGetAttribLocation(prog, "aUv")
        quad.position(0); GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 16, quad); GLES30.glEnableVertexAttribArray(aPos)
        quad.position(2); GLES30.glVertexAttribPointer(aUv, 2, GLES30.GL_FLOAT, false, 16, quad); GLES30.glEnableVertexAttribArray(aUv)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(prog, "uSt"), 1, false, stMatrix, 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(prog, "uCrop"), sx, sy)
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
uniform mat4 uSt;
uniform vec2 uCrop;
out vec2 vTex;
out vec2 vUv;
void main() {
  gl_Position = vec4(aPos, 0.0, 1.0);
  vec2 uv = (aUv - 0.5) * uCrop + 0.5;
  vTex = (uSt * vec4(uv, 0.0, 1.0)).xy;
  vUv = aUv;
}
"""
        private const val FRAG = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex;
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
in vec2 vTex;
in vec2 vUv;
out vec4 outColor;
float rand(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233)) + uSeed * 0.731) * 43758.5453); }
void main() {
  vec3 c = clamp(texture(uTex, vTex).rgb, 0.0, 1.0);
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
  outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
    }
}
