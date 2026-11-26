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
class RollFrame(private val clips: List<Clip>, private val index: Int, private val outW: Int, private val outH: Int, private val prevFrame: Bitmap?) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program()

    private inner class Program : BaseGlShaderProgram(false, 1) {
        private val prog = GlProgram(VERT, FRAG).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
        private var inW = 1; private var inH = 1
        private var firstUs = Long.MIN_VALUE
        private var prevTex = 0

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
            prog.use()
            prog.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            prog.setSamplerTexIdUniform("uPrev", if (prevTex != 0) prevTex else inputTexId, 1)
            val u = f.uniforms()
            prog.setFloatsUniform("uA", u[0]); prog.setFloatsUniform("uB", u[1]); prog.setFloatsUniform("uC", u[2])
            prog.setFloatsUniform("uP1", u[3]); prog.setFloatsUniform("uP2", if (prevTex != 0) u[4] else floatArrayOf(0f, 0f, 0f, 0f))
            prog.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        override fun release() {
            super.release()
            if (prevTex != 0) GLES20.glDeleteTextures(1, intArrayOf(prevTex), 0)
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
uniform vec4 uA; uniform vec4 uB; uniform vec4 uC; uniform vec4 uP1; uniform vec4 uP2;
varying vec2 vUv;
#define SRC(t) texture2D(uTexSampler, t)
#define PREV(t) texture2D(uPrev, vec2((t).x, 1.0 - (t).y))
""" + Scene.BODY + """
void main() { gl_FragColor = vec4(frameColor(vUv), 1.0); }
"""
    }
}
