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

/**
 * One look of the GNOME Shader Effects extension, split into the two halves this pod can
 * reproduce without reading the task's pixels: a colour matrix on the task leash
 * ([tintRows], applied by [RetroTint]) and an alpha overlay ([RetroOverlayView]).
 *
 * [shaderId] keeps the preset numbers of the extension's fragment shader, so the overlay's AGSL
 * keeps the same branches and the two can be compared line by line.
 */
enum class RetroPreset(
    val xmlName: String,
    val shaderId: Float,
    val animated: Boolean,
    /** The curved-crt look: the glass edge in the overlay plus the barrel of [RetroBarrel]. */
    val curved: Boolean,
    /** Row-major 3x3 colour matrix for the task, or null when the look keeps the colours. */
    val tintRows: FloatArray?,
) {
    VHS("vhs", 0f, animated = true, curved = false, tintRows = desaturate(keep = 0.82f)),
    NTSC("ntsc", 5f, animated = true, curved = false, tintRows = null),
    SCANLINES("scanlines", 1f, animated = false, curved = false, tintRows = null),
    CRT("crt", 2f, animated = true, curved = false, tintRows = null),
    CURVED_CRT("curved-crt", 6f, animated = true, curved = true, tintRows = null),
    GREEN_CRT("green-crt", 7f, animated = true, curved = false,
        tintRows = lumaTint(red = 0.18f, green = 1.0f, blue = 0.32f)),
    AMBER_CRT("amber-crt", 8f, animated = true, curved = false,
        tintRows = lumaTint(red = 1.0f, green = 0.65f, blue = 0.12f)),
    TECHNICOLOR("technicolor", 10f, animated = true, curved = false, tintRows = twoStripFilm());

    companion object {
        fun fromXmlName(name: String?): RetroPreset? = values().firstOrNull { it.xmlName == name }
    }
}

/** The shader's `ss_luma(color) * tint`: every output channel is the luma scaled by the tint. */
private fun lumaTint(red: Float, green: Float, blue: Float): FloatArray = floatArrayOf(
    red * LUMA_RED, red * LUMA_GREEN, red * LUMA_BLUE,
    green * LUMA_RED, green * LUMA_GREEN, green * LUMA_BLUE,
    blue * LUMA_RED, blue * LUMA_GREEN, blue * LUMA_BLUE,
)

/** The VHS branch's `mix(vec3(luma), color, 0.82)` with its own Rec.601 luma weights. */
private fun desaturate(keep: Float): FloatArray {
    val lose = 1f - keep
    return floatArrayOf(
        keep + lose * 0.299f, lose * 0.587f, lose * 0.114f,
        lose * 0.299f, keep + lose * 0.587f, lose * 0.114f,
        lose * 0.299f, lose * 0.587f, keep + lose * 0.114f,
    )
}

/**
 * Technicolor's two-strip colour (hunterk's film_noise): red stays, green and blue are mixed
 * halfway towards a cyan built as `(g + 0.7 b) / 2`. Written out, that is a linear map.
 */
private fun twoStripFilm(): FloatArray = floatArrayOf(
    1f, 0f, 0f,
    0f, 0.75f, 0.175f,
    0f, 0.175f, 0.6225f,
)

internal const val LUMA_RED = 0.2126f
internal const val LUMA_GREEN = 0.7152f
internal const val LUMA_BLUE = 0.0722f
