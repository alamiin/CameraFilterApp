package com.example.camerafilterapp.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.example.camerafilterapp.filter.FilterSettings
import com.example.camerafilterapp.filter.Look
import com.example.camerafilterapp.filter.LutCache
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The filter shader: clarity (sharpening), softness, 3D LUT grade, intensity and brightness, then
 * light leak, vignette, chromatic aberration and film grain. One instance per GL context.
 *
 * [external] selects the camera's samplerExternalOES input; otherwise a regular 2D texture
 * (still photos). Both variants share the same maths so preview, video and photos match.
 */
internal class FilterProgram(private val external: Boolean) {

    private val program = createProgram(VERTEX_SHADER, fragmentShader(external))
    private val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
    private val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
    private val uTexture = GLES20.glGetUniformLocation(program, "uTexture")
    private val uLut = GLES20.glGetUniformLocation(program, "uLut")
    private val uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
    private val uPosMatrix = GLES20.glGetUniformLocation(program, "uPosMatrix")
    private val uAspect = GLES20.glGetUniformLocation(program, "uAspect")
    private val uIntensity = GLES20.glGetUniformLocation(program, "uIntensity")
    private val uBrightness = GLES20.glGetUniformLocation(program, "uBrightness")
    private val uVignette = GLES20.glGetUniformLocation(program, "uVignette")
    private val uGrain = GLES20.glGetUniformLocation(program, "uGrain")
    private val uGrainCell = GLES20.glGetUniformLocation(program, "uGrainCell")
    private val uSeed = GLES20.glGetUniformLocation(program, "uSeed")
    private val uAberration = GLES20.glGetUniformLocation(program, "uAberration")
    private val uClarity = GLES20.glGetUniformLocation(program, "uClarity")
    private val uTexel = GLES20.glGetUniformLocation(program, "uTexel")
    private val uSoftness = GLES20.glGetUniformLocation(program, "uSoftness")
    private val uSoftTexel = GLES20.glGetUniformLocation(program, "uSoftTexel")
    private val uLeak = GLES20.glGetUniformLocation(program, "uLeak")
    private val uLeakPos = GLES20.glGetUniformLocation(program, "uLeakPos")

    private val lutTextures = HashMap<String, Int>()

    /**
     * Draws a full-viewport quad. The caller sets the viewport and target framebuffer.
     *
     * @param texMatrix maps output position (0..1) to input texture coordinates.
     * @param posMatrix maps output position (0..1) to upright image space (origin top-left), so
     *   positional effects such as light leaks land in the same place regardless of sensor rotation.
     * @param uprightAspect width / height of the upright image.
     * @param grainCell grain size in output pixels, so grain looks the same at any resolution.
     *   Also scales the clarity and softness radii for the same reason.
     * @param outputWidth viewport width in pixels.
     * @param outputHeight viewport height in pixels.
     */
    fun draw(
        settings: FilterSettings,
        inputTexture: Int,
        texMatrix: FloatArray,
        posMatrix: FloatArray,
        uprightAspect: Float,
        seed: Float,
        grainCell: Float,
        outputWidth: Int,
        outputHeight: Int,
    ) {
        val look = settings.look
        val intensity = if (look.isIdentity) 0f else settings.intensity

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, inputTexture)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture(look))
        GLES20.glUniform1i(uLut, 1)

        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glUniformMatrix4fv(uPosMatrix, 1, false, posMatrix, 0)
        GLES20.glUniform1f(uAspect, uprightAspect)
        GLES20.glUniform1f(uIntensity, intensity)
        GLES20.glUniform1f(uBrightness, settings.brightness)
        GLES20.glUniform1f(uVignette, look.vignette * intensity)
        GLES20.glUniform1f(uGrain, look.grain * intensity)
        GLES20.glUniform1f(uGrainCell, grainCell)
        GLES20.glUniform1f(uSeed, seed)
        GLES20.glUniform1f(uAberration, look.aberration * intensity)
        GLES20.glUniform1f(uClarity, look.clarity * intensity)
        val radius = CLARITY_RADIUS * grainCell
        GLES20.glUniform2f(uTexel, radius / outputWidth, radius / outputHeight)
        GLES20.glUniform1f(uSoftness, look.softness * intensity)
        val softRadius = SOFT_RADIUS * grainCell
        GLES20.glUniform2f(uSoftTexel, softRadius / outputWidth, softRadius / outputHeight)
        val leak = look.lightLeak
        if (leak != null) {
            GLES20.glUniform4f(uLeak, leak.color.r, leak.color.g, leak.color.b, leak.strength * intensity)
            GLES20.glUniform2f(uLeakPos, leak.x, leak.y)
        } else {
            GLES20.glUniform4f(uLeak, 0f, 0f, 0f, 0f)
        }

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, POSITIONS)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, TEX_COORDS)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    fun release() {
        GLES20.glDeleteProgram(program)
        if (lutTextures.isNotEmpty()) GLES20.glDeleteTextures(lutTextures.size, lutTextures.values.toIntArray(), 0)
        lutTextures.clear()
    }

    private fun lutTexture(look: Look): Int = lutTextures.getOrPut(look.id) {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, LutCache.WIDTH, LutCache.HEIGHT, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, LutCache.get(look),
        )
        tex[0]
    }

    private companion object {
        val POSITIONS = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val TEX_COORDS = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        /** Clarity sampling distance in output pixels (before [draw]'s grainCell scaling). */
        const val CLARITY_RADIUS = 1.5f

        /** Softness sampling distance in output pixels (before grainCell scaling); about skin-pore size at 1080p. */
        const val SOFT_RADIUS = 4f

        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vPos;
            void main() {
                gl_Position = aPosition;
                vPos = aTexCoord;
            }
        """

        fun fragmentShader(external: Boolean): String {
            val header = if (external) "#extension GL_OES_EGL_image_external : require\n" else ""
            val sampler = if (external) "samplerExternalOES" else "sampler2D"
            val n = LutCache.SIZE.toFloat()
            return header + """
                #ifdef GL_FRAGMENT_PRECISION_HIGH
                precision highp float;
                #else
                precision mediump float;
                #endif
                uniform $sampler uTexture;
                uniform sampler2D uLut;
                uniform mat4 uTexMatrix;
                uniform mat4 uPosMatrix;
                uniform float uAspect;
                uniform float uIntensity;
                uniform float uBrightness;
                uniform float uVignette;
                uniform float uGrain;
                uniform float uGrainCell;
                uniform float uSeed;
                uniform float uAberration;
                uniform float uClarity;
                uniform vec2 uTexel;
                uniform float uSoftness;
                uniform vec2 uSoftTexel;
                uniform vec4 uLeak;
                uniform vec2 uLeakPos;
                varying vec2 vPos;

                const float N = $n;

                vec3 sampleInput(vec2 p) {
                    return texture2D(uTexture, (uTexMatrix * vec4(p, 0.0, 1.0)).xy).rgb;
                }

                // 3D LUT stored as N blue slices of N x N (red across, green down).
                vec3 applyLut(vec3 c) {
                    float b = c.b * (N - 1.0);
                    float b0 = floor(b);
                    float b1 = min(b0 + 1.0, N - 1.0);
                    float x = c.r * (N - 1.0) + 0.5;
                    float y = (c.g * (N - 1.0) + 0.5) / N;
                    vec3 s0 = texture2D(uLut, vec2((b0 * N + x) / (N * N), y)).rgb;
                    vec3 s1 = texture2D(uLut, vec2((b1 * N + x) / (N * N), y)).rgb;
                    return mix(s0, s1, b - b0);
                }

                // Neighbours that differ in colour from the centre get little weight, so edges stay put.
                void softTap(vec3 centre, vec2 offset, inout vec3 sum, inout float weight) {
                    vec3 s = sampleInput(vPos + offset);
                    vec3 d = s - centre;
                    float w = exp(-dot(d, d) * 40.0);
                    sum += s * w;
                    weight += w;
                }

                float hash(vec2 p) {
                    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
                }

                void main() {
                    vec3 src;
                    if (uAberration > 0.0) {
                        vec2 d = (vPos - 0.5) * uAberration;
                        src = vec3(sampleInput(vPos + d).r, sampleInput(vPos).g, sampleInput(vPos - d).b);
                    } else {
                        src = sampleInput(vPos);
                    }

                    if (uClarity > 0.0) {
                        vec3 blur = (sampleInput(vPos + vec2(uTexel.x, 0.0)) + sampleInput(vPos - vec2(uTexel.x, 0.0))
                            + sampleInput(vPos + vec2(0.0, uTexel.y)) + sampleInput(vPos - vec2(0.0, uTexel.y))) * 0.25;
                        // Unsharp mask on luminance only, so edges don't pick up colour fringes. The clamp
                        // stops halos on hard edges and keeps sensor noise from being boosted too far.
                        float detail = clamp(dot(src - blur, vec3(0.299, 0.587, 0.114)), -0.15, 0.15);
                        src = clamp(src + detail * uClarity * 2.0, 0.0, 1.0);
                    }

                    if (uSoftness > 0.0) {
                        // Edge-preserving blur: axis taps at the full radius, diagonals at half, so the
                        // 8 samples cover the disc without a visible ring.
                        vec3 sum = src;
                        float weight = 1.0;
                        vec2 t = uSoftTexel;
                        vec2 h = uSoftTexel * 0.5;
                        softTap(src, vec2(t.x, 0.0), sum, weight);
                        softTap(src, vec2(-t.x, 0.0), sum, weight);
                        softTap(src, vec2(0.0, t.y), sum, weight);
                        softTap(src, vec2(0.0, -t.y), sum, weight);
                        softTap(src, h, sum, weight);
                        softTap(src, -h, sum, weight);
                        softTap(src, vec2(h.x, -h.y), sum, weight);
                        softTap(src, vec2(-h.x, h.y), sum, weight);
                        vec3 blurred = sum / weight;
                        src = mix(src, blurred, uSoftness * 0.8);
                        // Diffusion-filter glow: bright areas bleed a little light into their surroundings.
                        float glow = smoothstep(0.6, 1.0, dot(blurred, vec3(0.299, 0.587, 0.114))) * uSoftness * 0.35;
                        src = 1.0 - (1.0 - src) * (1.0 - blurred * glow);
                    }

                    vec3 c = mix(src, applyLut(clamp(src, 0.0, 1.0)), uIntensity);
                    c = clamp(c + uBrightness, 0.0, 1.0);

                    vec2 up = (uPosMatrix * vec4(vPos, 0.0, 1.0)).xy;
                    vec2 aspect = vec2(uAspect, 1.0);

                    if (uLeak.a > 0.0) {
                        vec2 lp = (up - uLeakPos) * aspect / max(uAspect, 1.0);
                        float glow = exp(-dot(lp, lp) * 4.0) * uLeak.a;
                        c = 1.0 - (1.0 - c) * (1.0 - uLeak.rgb * glow);
                    }

                    if (uVignette > 0.0) {
                        vec2 vp = (up - 0.5) * aspect;
                        float d = length(vp) / length(0.5 * aspect);
                        c *= 1.0 - smoothstep(0.4, 1.1, d) * uVignette;
                    }

                    if (uGrain > 0.0) {
                        float noise = hash(floor(gl_FragCoord.xy / uGrainCell) + uSeed) - 0.5;
                        float l = dot(c, vec3(0.299, 0.587, 0.114));
                        // Strongest in the midtones, like film.
                        c += noise * uGrain * (1.0 - abs(l - 0.5) * 1.4);
                    }

                    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
                }
            """
        }

        fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] != GLES20.GL_TRUE) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                error("Program link failed: $log")
            }
            return program
        }

        fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] != GLES20.GL_TRUE) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                error("Shader compile failed: $log")
            }
            return shader
        }

        fun floatBuffer(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }
    }
}

/**
 * 2D affine map on normalised coordinates: x' = a*x + b*y + c, y' = d*x + e*y + f.
 * Used to build the texture and position matrices without sign mistakes.
 */
internal data class Affine(
    val a: Float, val b: Float, val c: Float,
    val d: Float, val e: Float, val f: Float,
) {
    /** Returns the map "apply [inner] first, then this". */
    operator fun times(inner: Affine) = Affine(
        a * inner.a + b * inner.d, a * inner.b + b * inner.e, a * inner.c + b * inner.f + c,
        d * inner.a + e * inner.d, d * inner.b + e * inner.e, d * inner.c + e * inner.f + f,
    )

    /** Column-major 4x4 for glUniformMatrix4fv / android.opengl.Matrix. */
    fun toMat4() = floatArrayOf(
        a, d, 0f, 0f,
        b, e, 0f, 0f,
        0f, 0f, 1f, 0f,
        c, f, 0f, 1f,
    )

    companion object {
        val IDENTITY = Affine(1f, 0f, 0f, 0f, 1f, 0f)

        /** Image coordinates (origin top-left) from GL coordinates (origin bottom-left). */
        val FLIP_Y = Affine(1f, 0f, 0f, 0f, -1f, 1f)

        /** Mirror horizontally. */
        val FLIP_X = Affine(-1f, 0f, 1f, 0f, 1f, 0f)

        /**
         * Maps sensor-oriented image coordinates to upright ones when the image must be
         * rotated [degrees] clockwise (CameraX's rotationDegrees convention).
         */
        fun rotateClockwise(degrees: Int): Affine = when (((degrees % 360) + 360) % 360) {
            90 -> Affine(0f, -1f, 1f, 1f, 0f, 0f) // (x, y) -> (1 - y, x)
            180 -> Affine(-1f, 0f, 1f, 0f, -1f, 1f) // (x, y) -> (1 - x, 1 - y)
            270 -> Affine(0f, 1f, 0f, -1f, 0f, 1f) // (x, y) -> (y, 1 - x)
            else -> IDENTITY
        }

        /** Inverse of [rotateClockwise]: upright coordinates back to sensor-oriented ones. */
        fun unrotateClockwise(degrees: Int): Affine = rotateClockwise(360 - degrees)

        /** Scales around the centre, e.g. to centre-crop. */
        fun scaleAboutCentre(sx: Float, sy: Float) = Affine(sx, 0f, 0.5f - 0.5f * sx, 0f, sy, 0.5f - 0.5f * sy)
    }
}
