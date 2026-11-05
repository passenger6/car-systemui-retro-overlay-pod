/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.car.retro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Region
import android.graphics.RuntimeShader
import android.util.AttributeSet
import android.util.Log
import android.view.View

/**
 * The alpha half of a preset: scanlines, vignette, flicker, grain, tracking bar, film scratches
 * and the curved glass edge, drawn by one AGSL shader as black-with-alpha (darkening) and
 * white-with-alpha (lightening). It never reads the task's pixels, so SurfaceFlinger composes it
 * with plain SrcOver over the task leash and the app underneath keeps rendering on its own.
 *
 * The shader keeps the preset branches of the GNOME extension's fragment shader
 * (shaderSource.js) and drops every line that samples a neighbour or moves the coordinate.
 *
 * Touch: once attached, the view hands its whole surface back with an empty touchable region,
 * so taps land on the task below. The DecorPanel host window is TYPE_APPLICATION without the spy
 * input feature (AutoDecor.java), which would otherwise swallow them.
 */
class RetroOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val shader = RuntimeShader(OVERLAY_AGSL)
    // Not `Paint().apply { setShader(shader) }`: inside apply, `shader` is the Paint's own
    // (null) getter, the view's field is shadowed, and drawRect then fills opaque black.
    private val paint = Paint().also { it.shader = shader }
    private var animated = false
    // Hiding the panel hides its leash, not the view: HWUI would keep drawing 60 frames a
    // second into a surface nobody sees. The controller switches frames off with the look.
    private var framesEnabled = false

    init {
        // A View skips onDraw until it has a background or asks for it explicitly.
        setWillNotDraw(false)
        shader.setFloatUniform("resolution", 1f, 1f)
        shader.setFloatUniform("time", 0f)
        shader.setFloatUniform("intensity", 0f)
        shader.setFloatUniform("curved", 0f)
        shader.setFloatUniform("glassRadius", -1f)
        shader.setFloatUniform("preset", RetroPreset.SCANLINES.shaderId)
        shader.setFloatUniform("spacing", 6f)
        shader.setFloatUniform("noise", 0f)
        shader.setFloatUniform("noiseSize", 1f)
        shader.setFloatUniform("tracking", 0f)
        shader.setFloatUniform("trackingBar", -1f, 0.03f, 0f)
    }

    /**
     * View thread. [glassRadiusPx] is the corner radius of the glass edge in view pixels: the
     * target panel's own radius when the look is on one panel, negative for the default bezel.
     */
    fun configure(knobs: RetroKnobs, glassRadiusPx: Float) {
        shader.setFloatUniform("preset", knobs.preset.shaderId)
        shader.setFloatUniform("intensity", knobs.intensity)
        shader.setFloatUniform("curved", if (knobs.isCurved) 1f else 0f)
        shader.setFloatUniform("glassRadius", glassRadiusPx)
        shader.setFloatUniform("spacing", knobs.scanlineSpacingPx)
        shader.setFloatUniform("noise", knobs.noise)
        shader.setFloatUniform("noiseSize", knobs.noiseSizePx)
        shader.setFloatUniform("tracking", knobs.tracking)
        // Grain and the bar move, so they need frames even for a still preset.
        animated = knobs.animate && (knobs.preset.animated || knobs.noise > 0f || knobs.tracking > 0f)
        Log.i(TAG, "[retro] overlay configured: $knobs glassRadius=$glassRadiusPx animated=$animated")
        invalidate()
    }

    /** View thread. Frames run only while the look is on; off, the view draws once and stops. */
    fun setFramesEnabled(enabled: Boolean) {
        if (framesEnabled == enabled) return
        framesEnabled = enabled
        Log.i(TAG, "[retro] overlay frames ${if (enabled) "enabled" else "disabled"}")
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val host = rootSurfaceControl
        if (host == null) {
            Log.w(TAG, "[retro] no root surface control at attach, touches will be swallowed")
        } else {
            host.setTouchableRegion(Region())
            Log.i(TAG, "[retro] touchable region cleared, taps pass through to the task")
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        shader.setFloatUniform("resolution", width.toFloat(), height.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        // Same clock and same bar as the barrel's strips, so view and mirrors agree.
        val seconds = RetroNoise.seconds()
        val bar = RetroNoise.trackingBar(seconds)
        shader.setFloatUniform("time", seconds)
        shader.setFloatUniform("trackingBar", bar.center, bar.width, bar.strength)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        if (animated && framesEnabled) {
            postInvalidateOnAnimation()
        }
    }

    companion object {
        private const val TAG = "RetroOverlayView"

        /**
         * Every branch yields `darken` (what is left of the content, 0..1) and `lighten` (light
         * added on top). SrcOver draws exactly that as colour = lighten, alpha = 1 - darken, and
         * the extension's `mix(clean, color, intensity)` becomes scaling both by intensity.
         * Output is premultiplied, as RuntimeShader expects.
         */
        private val OVERLAY_AGSL = """
            uniform float2 resolution;
            uniform float time;
            uniform float intensity;
            // 1 = curved glass: the rounded edge below; the barrel itself is RetroBarrel's.
            uniform float curved;
            uniform float glassRadius;
            uniform float preset;
            uniform float spacing;
            uniform float noise;
            uniform float noiseSize;
            uniform float tracking;
            // The VHS bar for this frame: centre (0..1 of the height), width, strength. Computed
            // on the CPU (RetroNoise.trackingBar) so the barrel's strips tear at the same rows.
            uniform float3 trackingBar;

            float ss_random(float2 point) {
                return fract(sin(dot(point, float2(12.9898, 78.233))) * 43758.5453);
            }

            float ss_scanline(float y) {
                float line = 0.5 + 0.5 * cos(y * resolution.y * 6.2831853 / spacing);
                return 0.72 + 0.28 * line;
            }

            float ss_tracking(float y) {
                float width = trackingBar.y;
                return (1.0 - smoothstep(width * 0.15, width, abs(y - trackingBar.x)))
                    * trackingBar.z;
            }

            float2 ss_crt_uv(float2 uv) {
                float2 point = uv * 2.0 - 1.0;
                return point * (1.0 + 0.028 * intensity * point.yx * point.yx) * 0.5 + 0.5;
            }

            // The aperture mask keeps one channel per column; SrcOver dims all channels alike,
            // so two columns out of three go dark instead.
            float ss_mask(float2 uv, float base) {
                float stripe = mod(floor(uv.x * resolution.x), 3.0);
                return stripe < 0.5 ? 1.0 : base;
            }

            half4 main(float2 coord) {
                float2 uv = coord / resolution;
                // The tracking bar drags the lines with it: while it passes, rows within
                // the bar shift up to 2 px. The shift moves this overlay's own pattern, not
                // the app's pixels, so it needs nothing from the task.
                float bar = tracking > 0.0 ? ss_tracking(uv.y) * tracking : 0.0;
                coord.y += bar * 2.0 * (ss_random(float2(floor(time * 30.0), uv.y)) - 0.5);
                uv = coord / resolution;
                float line = 0.5 + 0.5 * cos(coord.y * 6.2831853 / spacing);
                float darken = 1.0;
                float lighten = 0.0;
                if (preset < 0.5) {
                    // VHS: grain, tracking bar, head noise; the horizontal jitter is dropped.
                    float boost = smoothstep(0.55, 1.0, intensity);
                    float frame = floor(time * 30.0);
                    float vhsBar = ss_tracking(uv.y);
                    float grain = ss_random(floor(coord) + float2(frame, frame * 0.73)) - 0.5;
                    float headNoise = smoothstep(0.965, 1.0, uv.y);
                    float added = grain * mix(0.12, 0.22, boost)
                        + vhsBar * mix(0.045, 0.08, boost)
                        + headNoise * grain * mix(0.35, 0.55, boost);
                    darken = (1.0 - mix(0.09, 0.18, boost) * (1.0 - line)) * (1.0 + min(added, 0.0));
                    lighten = max(added, 0.0);
                } else if (preset < 1.5) {
                    // Scanlines, one to one.
                    darken = 0.60 + 0.40 * line;
                } else if (preset < 2.5 || (preset > 6.5 && preset < 8.5)) {
                    // CRT, CRT (Green), CRT (Amber): the tint lives in the colour matrix.
                    float2 centered = uv * 2.0 - 1.0;
                    float vignette = max(1.0 - 0.22 * dot(centered, centered), 0.0);
                    darken = ss_mask(uv, 0.82) * ss_scanline(uv.y) * vignette
                        * (1.0 + 0.012 * sin(time * 47.0));
                } else if (preset < 5.5) {
                    // NTSC: only its scanlines survive without the composite signal.
                    darken = ss_scanline(uv.y);
                } else if (preset < 6.5) {
                    // CRT (Curved): a lighter CRT; its glass edge is the `curved` block below,
                    // the barrel itself is built from mirrors by RetroBarrel.
                    float2 point = uv * 2.0 - 1.0;
                    float vignette = 1.0 - 0.12 * dot(point, point);
                    darken = ss_mask(uv, 0.88) * ss_scanline(uv.y) * vignette
                        * (1.0 + 0.006 * sin(time * 47.0));
                } else {
                    // Technicolor: grain, vignette, flicker, hotspot, scratches; the 1 px
                    // deconvergence and gate weave are dropped.
                    float frame = mod(floor(time * 24.0), 4268.0);
                    float grain = ss_random(floor(coord) + float2(frame, frame * 0.73)) - 0.5;
                    float vignette = smoothstep(0.0, 0.85, length(uv - 0.5) / 1.25);
                    float flicker = 1.0 + 0.06 * (ss_random(float2(frame, 73.0)) - 0.5);
                    float scratchFrame = floor(frame / 3.0);
                    float scratchX = ss_random(float2(scratchFrame, 41.0));
                    float scratch = (1.0 - smoothstep(0.4, 1.1, abs(uv.x - scratchX) * resolution.x))
                        * 0.22 * step(0.88, ss_random(float2(scratchFrame, 63.0)));
                    float added = grain * 0.12 + (1.0 - vignette) * 0.035 + scratch;
                    darken = (1.0 - vignette) * flicker * (1.0 + min(added, 0.0));
                    lighten = max(added, 0.0);
                }
                // Added on top of any preset: the curved glass edge. Always on for curved-crt, a
                // knob for the rest. `half` is a type in AGSL, hence halfSize.
                // Whole display (glassRadius < 0): a rounded rectangle on the warped coordinate
                // of the extension's curved-crt, radius 6 % of the shorter half-size, black
                // beyond it, so the dark corners sit where the barrel's rows end: the bezel.
                // One panel (glassRadius >= 0): the panel's own rounded corner on the straight
                // coordinate, and beyond it nothing at all. The display around the panel is not
                // the tube's black, and the panel's leash is already cut at that corner.
                if (curved > 0.5) {
                    float2 halfSize = resolution * 0.5;
                    if (glassRadius < 0.0) {
                        float2 bent = ss_crt_uv(uv) * 2.0 - 1.0;
                        float radius = 0.06 * min(halfSize.x, halfSize.y);
                        float2 toCorner = abs(bent) * halfSize - (halfSize - float2(radius));
                        float distanceToEdge = length(max(toCorner, float2(0.0))) - radius;
                        darken *= 1.0 - smoothstep(-2.0, 0.0, distanceToEdge);
                    } else {
                        float2 straight = uv * 2.0 - 1.0;
                        float2 toCorner = abs(straight) * halfSize - (halfSize - float2(glassRadius));
                        float distanceToEdge = length(max(toCorner, float2(0.0))) - glassRadius;
                        // The fade starts at the edge, not around it: the leash's own corner is
                        // anti-aliased over the same pixel, and a fade centred on the edge left
                        // that pixel half-treated, a bright 1 px rim along the arc.
                        float outside = smoothstep(0.0, 1.5, distanceToEdge);
                        darken = mix(darken, 1.0, outside);
                        lighten = mix(lighten, 0.0, outside);
                    }
                }
                // Added on top of any preset: film grain and the VHS tracking bar. Both are
                // stronger than the extension's VHS numbers on purpose, they are the "this is
                // retro" cue. Grain is new every frame; the bar is light with noise inside and
                // a darker edge below it.
                if (noise > 0.0) {
                    float frame = floor(time * 30.0);
                    float grain = ss_random(floor(coord / noiseSize) + float2(frame, frame * 0.73)) - 0.5;
                    float added = grain * 0.5 * noise;
                    darken *= 1.0 + min(added, 0.0);
                    lighten += max(added, 0.0);
                }
                if (bar > 0.0) {
                    float frame = floor(time * 30.0);
                    float barNoise = ss_random(float2(frame, floor(coord.y))) - 0.5;
                    lighten += bar * (0.35 + 0.5 * max(barNoise, 0.0));
                    darken *= 1.0 - bar * 0.4 * max(-barNoise, 0.0);
                }
                // An overlay cannot brighten the content, only add light on top.
                darken = clamp(darken, 0.0, 1.0);
                float alpha = (1.0 - darken) * intensity;
                float light = clamp(lighten, 0.0, 1.0) * intensity;
                // Premultiplied: colour may not exceed alpha.
                alpha = max(alpha, light);
                return half4(half3(light), half(alpha));
            }
        """.trimIndent()
    }
}
