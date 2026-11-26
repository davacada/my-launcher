package com.davacada.roll

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Export: puts clip [index] into the [outW] × [outH] frame the way the viewer shows it (fill or fit,
 * background, position, scale, rotation, flip, Ken Burns) and draws its transitions.
 * [prevFrame] is the last frame of the clip before, for a dissolve.
 */
@UnstableApi
class RollFrame(private val clips: List<Clip>, private val index: Int, private val outW: Int, private val outH: Int, private val prevFrame: Bitmap?,
                private val fx: FloatArray = FloatArray(4), private val hide: Boolean = false,
                /** V2 / V3: their frames, which tracks show, and where this clip starts in the edit. */
                private val overlays: OverlayFrames? = null, private val shownTracks: List<Boolean> = emptyList(), private val clipStartMs: Long = 0) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program()

    private inner class Program : BaseGlShaderProgram(false, 1) {
        private val prog = GlProgram(VERT, FRAG).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
        private var inW = 1; private var inH = 1
        private var firstUs = Long.MIN_VALUE
        private var prevTex = 0
        private val layerTex = IntArray(2); private val layerBmp = arrayOfNulls<Bitmap>(2)

        override fun configure(inputWidth: Int, inputHeight: Int): Size { inW = inputWidth; inH = inputHeight; return Size(outW, outH) }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            if (firstUs == Long.MIN_VALUE) firstUs = presentationTimeUs
            val local = (presentationTimeUs - firstUs) / 1000
            val f = Scene.frame(clips, index, local, outW.toFloat() / outH, inW.toFloat() / inH)
            if (prevTex == 0 && prevFrame != null && !prevFrame.isRecycled) {
                val t = IntArray(1); GLES20.glGenTextures(1, t, 0); prevTex = t[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTex)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, prevFrame, 0)
            }
            // V2 / V3 frames for this moment
            val layerU = Array(2) { floatArrayOf(1f, 0f, 1f, 0f) to floatArrayOf(0f, 0f, 0f, 0f) }
            overlays?.let { ov ->
                val t = clipStartMs + local
                for (k in 0..1) {
                    if (shownTracks.getOrElse(k) { true } == false) continue
                    val o = ov.clips.lastOrNull { it.track == k && t >= it.at && t < it.endAt } ?: continue
                    val bm = ov.frame(o, o.startMs + ((t - o.at) * o.speed).toLong()) ?: continue
                    if (layerTex[k] == 0) {
                        val tt = IntArray(1); GLES20.glGenTextures(1, tt, 0); layerTex[k] = tt[0]
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, layerTex[k])
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                    }
                    if (layerBmp[k] !== bm) { GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, layerTex[k]); GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bm, 0); layerBmp[k] = bm }
                    layerU[k] = floatArrayOf(o.aspect, if (o.fit) 1f else 0f, o.scale, (o.rot * Math.PI / 180).toFloat()) to floatArrayOf(o.ox, o.oy, if (o.flip) 1f else 0f, o.opacity)
                }
            }
            prog.use()
            for (k in 0..1) {
                prog.setSamplerTexIdUniform("uL$k", if (layerTex[k] != 0) layerTex[k] else inputTexId, 2 + k)
                prog.setFloatsUniform("uL${k}A", layerU[k].first); prog.setFloatsUniform("uL${k}B", if (layerTex[k] != 0) layerU[k].second else floatArrayOf(0f, 0f, 0f, 0f))
            }
            prog.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            prog.setSamplerTexIdUniform("uPrev", if (prevTex != 0) prevTex else inputTexId, 1)
            val u = f.uniforms()
            prog.setFloatsUniform("uA", u[0]); prog.setFloatsUniform("uB", u[1]); prog.setFloatsUniform("uC", u[2])
            prog.setFloatsUniform("uE", fx)
            prog.setFloatUniform("uHide", if (hide) 1f else 0f)
            prog.setFloatsUniform("uP1", u[3]); prog.setFloatsUniform("uP2", if (prevTex != 0) u[4] else floatArrayOf(0f, 0f, 0f, 0f))
            prog.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        override fun release() {
            super.release()
            if (prevTex != 0) GLES20.glDeleteTextures(1, intArrayOf(prevTex), 0)
            for (k in 0..1) if (layerTex[k] != 0) GLES20.glDeleteTextures(1, intArrayOf(layerTex[k]), 0)
            prog.delete()
        }
    }

    companion object {
        private const val VERT = """
attribute vec4 aFramePosition;
varying vec2 vUv;
void main() { gl_Position = aFramePosition; vUv = aFramePosition.xy * 0.5 + 0.5; }
"""
        private val FRAG = """
precision highp float;
uniform sampler2D uTexSampler;
uniform sampler2D uPrev;
uniform vec4 uA; uniform vec4 uB; uniform vec4 uC; uniform vec4 uP1; uniform vec4 uP2; uniform vec4 uE;
uniform float uHide;
uniform sampler2D uL0; uniform sampler2D uL1;
uniform vec4 uL0A; uniform vec4 uL0B; uniform vec4 uL1A; uniform vec4 uL1B;
varying vec2 vUv;
#define SRC(t) texture2D(uTexSampler, t)
#define PREV(t) texture2D(uPrev, vec2((t).x, 1.0 - (t).y))
""" + Scene.BODY + """
vec3 layer(vec3 c, vec2 uv, sampler2D s, vec4 A, vec4 B) {
  if (B.w < 0.001) return c;
  vec2 t = mapUv(uv, A.x, uA.y, A.y, A.z, B.xy, A.w, B.z);
  if (!inside(t)) return c;
  return mix(c, texture2D(s, vec2(t.x, 1.0 - t.y)).rgb, B.w);
}
void main() {
  vec3 c = uHide > 0.5 ? vec3(0.0) : frameColor(vUv);
  c = layer(c, vUv, uL0, uL0A, uL0B);
  c = layer(c, vUv, uL1, uL1A, uL1B);
  gl_FragColor = vec4(c, 1.0);
}
"""
    }
}
