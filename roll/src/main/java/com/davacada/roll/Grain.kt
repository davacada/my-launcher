package com.davacada.roll

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/** Film grain (moving, strongest in the mid-tones) and a soft vignette, drawn on the phone's GPU. */
@UnstableApi
class GrainEffect(private val grain: Float, private val vignette: Float) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = GrainProgram(grain, vignette)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = grain <= 0.001f && vignette <= 0.001f
}

@UnstableApi
private class GrainProgram(private val grain: Float, private val vignette: Float) : BaseGlShaderProgram(false, 1) {
    private val program: GlProgram
    private var w = 1; private var h = 1

    init {
        try {
            program = GlProgram(VERTEX, FRAGMENT)
        } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
        program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size { w = inputWidth; h = inputHeight; return Size(inputWidth, inputHeight) }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatUniform("uGrain", grain * 0.16f)
            program.setFloatUniform("uVignette", vignette)
            program.setFloatUniform("uSeed", ((presentationTimeUs / 41_667L) % 997L).toFloat())
            program.setFloatsUniform("uSize", floatArrayOf(w.toFloat(), h.toFloat()))
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e, presentationTimeUs) }
    }

    override fun release() {
        super.release()
        try { program.delete() } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
    }

    companion object {
        private const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexCoords;
void main() {
  gl_Position = aFramePosition;
  vTexCoords = aFramePosition.xy * 0.5 + 0.5;
}
"""
        private const val FRAGMENT = """
precision highp float;
uniform sampler2D uTexSampler;
uniform float uGrain;
uniform float uVignette;
uniform float uSeed;
uniform vec2 uSize;
varying vec2 vTexCoords;
float rand(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233)) + uSeed * 0.731) * 43758.5453); }
void main() {
  vec4 c = texture2D(uTexSampler, vTexCoords);
  vec2 cell = floor(vTexCoords * uSize / 1.6);
  float n = (rand(cell) + rand(cell + vec2(17.0, 31.0)) - 1.0);
  float l = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
  c.rgb += n * uGrain * (1.0 - abs(l - 0.5) * 1.3);
  vec2 d = vTexCoords - 0.5;
  c.rgb *= 1.0 - uVignette * smoothstep(0.15, 0.75, dot(d, d) * 2.0);
  gl_FragColor = vec4(clamp(c.rgb, 0.0, 1.0), c.a);
}
"""
    }
}
