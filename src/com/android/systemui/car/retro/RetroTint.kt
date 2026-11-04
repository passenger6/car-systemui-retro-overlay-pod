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

import android.view.SurfaceControl

/**
 * The colour half of a preset: one 3x3 matrix on the task leash, multiplied in by SurfaceFlinger
 * at composition, so the app keeps drawing as usual and nothing is copied.
 *
 * [SurfaceControl.Transaction.setColorTransform] hands its nine floats to `ui::mat3`, which is
 * column-major (frameworks/native/libs/math TMatHelpers.h). Presets are written row-major so
 * they read like the shader; [apply] transposes in one place.
 */
object RetroTint {
    private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val NO_TRANSLATION = floatArrayOf(0f, 0f, 0f)

    /** The shader's final `result = vec3(ss_luma(result))`: every channel becomes the luma. */
    private val MONOCHROME_ROWS = floatArrayOf(
        LUMA_RED, LUMA_GREEN, LUMA_BLUE,
        LUMA_RED, LUMA_GREEN, LUMA_BLUE,
        LUMA_RED, LUMA_GREEN, LUMA_BLUE,
    )

    fun apply(
        transaction: SurfaceControl.Transaction,
        leash: SurfaceControl,
        preset: RetroPreset,
        intensity: Float,
        monochrome: Boolean,
    ) {
        // The shader ends with mix(clean, color, intensity); for a linear tint that is the same
        // as blending the matrix towards identity.
        var rows = preset.tintRows?.let { lerp(IDENTITY, it, intensity) } ?: IDENTITY
        if (monochrome) {
            rows = multiply(MONOCHROME_ROWS, rows)
        }
        transaction.setColorTransform(leash, columnMajor(rows), NO_TRANSLATION)
    }

    fun clear(transaction: SurfaceControl.Transaction, leash: SurfaceControl) {
        transaction.setColorTransform(leash, columnMajor(IDENTITY), NO_TRANSLATION)
    }

    private fun lerp(from: FloatArray, to: FloatArray, amount: Float): FloatArray =
        FloatArray(9) { index -> from[index] + (to[index] - from[index]) * amount }

    /** Row-major product `left * right`, applied right first. */
    private fun multiply(left: FloatArray, right: FloatArray): FloatArray =
        FloatArray(9) { index ->
            val row = index / 3
            val column = index % 3
            left[row * 3] * right[column] +
                left[row * 3 + 1] * right[3 + column] +
                left[row * 3 + 2] * right[6 + column]
        }

    private fun columnMajor(rows: FloatArray): FloatArray =
        FloatArray(9) { index -> rows[(index % 3) * 3 + index / 3] }
}
