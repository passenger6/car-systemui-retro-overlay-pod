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

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * The extension's `ss_random` and `ss_tracking`, on the CPU, plus one clock for everything that
 * moves. The overlay view and the barrel's strips both read them, so the tracking bar the view
 * draws and the rows the strips tear at are the same bar at the same height.
 *
 * The bar's per-cycle numbers are computed here and handed to the shader as a uniform rather
 * than recomputed in AGSL: `sin` of a large argument in GPU float lands on a different fraction
 * than on the CPU, and the two would drift apart.
 */
object RetroNoise {

    private val originMillis = SystemClock.uptimeMillis()

    /** Seconds since the pod loaded; small enough to stay exact at 1/30 s in a float. */
    fun seconds(): Float = (SystemClock.uptimeMillis() - originMillis) / 1000f

    /** `fract(sin(dot(point, (12.9898, 78.233))) * 43758.5453)`. */
    fun random(x: Float, y: Float): Float {
        val value = sin((x * 12.9898f + y * 78.233f).toDouble()) * 43758.5453
        return (value - floor(value)).toFloat()
    }

    /** One pass of the VHS tracking bar: where it is (0..1 of the height), how wide, how strong. */
    class TrackingBar(val center: Float, val width: Float, val strength: Float)

    fun trackingBar(seconds: Float): TrackingBar {
        val cycle = floor(seconds / 9f)
        val delay = 0.4f + 2f * random(cycle, 17f)
        val duration = 3f + 2f * random(cycle, 29f)
        val progress = ((seconds - cycle * 9f - delay) / duration).coerceIn(0f, 1f)
        val width = 0.028f + 0.037f * random(cycle, 43f)
        val strength = 0.45f + 0.5f * random(cycle, 61f)
        val center = -0.12f + (1.12f - -0.12f) * progress
        return TrackingBar(center, width, strength)
    }

    /** The bar's weight at height [y] (0..1), as the shader's `ss_tracking`. */
    fun tracking(y: Float, bar: TrackingBar): Float =
        (1f - smoothstep(bar.width * 0.15f, bar.width, abs(y - bar.center))) * bar.strength

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun mix(from: Float, to: Float, amount: Float): Float = from + (to - from) * amount
}
