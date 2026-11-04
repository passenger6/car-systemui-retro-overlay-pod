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

import android.content.res.Resources
import android.os.SystemProperties
import android.util.Log

/**
 * Every setting of the look, read in one place: the pod's resources (an RRO overrides them per
 * project) and, over those, `debug.retro.<name>` system properties for trying values on a
 * running device without a build:
 *
 *     adb shell setprop debug.retro.preset vhs
 *     adb shell cmd statusbar carsysui-dispatch-event retro_off
 *     adb shell cmd statusbar carsysui-dispatch-event retro_on
 *
 * The controller reads the knobs every time the look is put on, so a property set between an
 * off and an on is what the on uses. `debug.` properties are readable by every process without
 * a permission (`get_prop(domain, debug_prop)` in sepolicy) and a reboot clears them.
 */
class RetroKnobs private constructor(
    val preset: RetroPreset,
    /** 0..1, the extension's intensity. */
    val intensity: Float,
    /** The `retro_curved` knob alone; [isCurved] is what the look uses. */
    val curved: Boolean,
    val monochrome: Boolean,
    val animate: Boolean,
    val scanlineSpacingPx: Float,
    /** 0..1 each; 0 = off. */
    val noise: Float,
    val noiseSizePx: Float,
    val tracking: Float,
    val jitter: Float,
    val barrelStrips: Int,
    /** Multiplier on the extension's `0.028 * intensity`; 1 = as the extension. */
    val barrelBulgeMultiplier: Float,
    val barrelCover: Boolean,
    val barrelFit: Boolean,
    val barrelOutward: Boolean,
) {
    /** Curved glass: the curved-crt preset is, any other preset is when the knob says so. */
    val isCurved: Boolean
        get() = curved || preset.curved

    /** The barrel's curvature: flat looks get strips only for the shake. */
    val barrelBulge: Float
        get() = if (isCurved) BARREL_BULGE * intensity * barrelBulgeMultiplier else 0f

    /** Whether the mirror strips are needed at all. */
    val wantsBarrel: Boolean
        get() = barrelStrips > 0 && (isCurved || jitter > 0f)

    override fun toString(): String =
        "preset=${preset.xmlName} intensity=$intensity curved=$isCurved monochrome=$monochrome" +
            " animate=$animate" +
            " spacing=$scanlineSpacingPx noise=$noise/${noiseSizePx}px tracking=$tracking" +
            " jitter=$jitter strips=$barrelStrips bulge=$barrelBulge cover=$barrelCover" +
            " fit=$barrelFit outward=$barrelOutward"

    companion object {
        private const val TAG = "RetroKnobs"
        private const val PROPERTY_PREFIX = "debug.retro."

        /** `0.028 * intensity` in the extension's ss_crt_uv. */
        const val BARREL_BULGE = 0.028f

        fun read(resources: Resources): RetroKnobs {
            val presetName = string(resources, R.string.retro_preset, "preset")
            val preset = RetroPreset.fromXmlName(presetName) ?: run {
                Log.w(TAG, "[retro] unknown preset '$presetName', falling back to scanlines")
                RetroPreset.SCANLINES
            }
            return RetroKnobs(
                preset = preset,
                intensity = percent(resources, R.integer.retro_intensity_percent, "intensity"),
                curved = bool(resources, R.bool.retro_curved, "curved"),
                monochrome = bool(resources, R.bool.retro_monochrome, "monochrome"),
                animate = bool(resources, R.bool.retro_animate, "animate"),
                scanlineSpacingPx = int(resources, R.integer.retro_scanline_spacing_px, "spacing")
                    .coerceAtLeast(1).toFloat(),
                noise = percent(resources, R.integer.retro_noise_percent, "noise"),
                noiseSizePx = int(resources, R.integer.retro_noise_size_px, "noise_size")
                    .coerceAtLeast(1).toFloat(),
                tracking = percent(resources, R.integer.retro_tracking_percent, "tracking"),
                jitter = percent(resources, R.integer.retro_jitter_percent, "jitter"),
                barrelStrips = int(resources, R.integer.retro_barrel_strips, "strips").coerceAtLeast(0),
                barrelBulgeMultiplier = int(resources, R.integer.retro_barrel_bulge_percent, "bulge")
                    .coerceAtLeast(0) / 100f,
                barrelCover = bool(resources, R.bool.retro_barrel_cover, "cover"),
                barrelFit = bool(resources, R.bool.retro_barrel_fit, "fit"),
                barrelOutward = bool(resources, R.bool.retro_barrel_outward, "outward"),
            )
        }

        private fun property(name: String): String? =
            SystemProperties.get(PROPERTY_PREFIX + name, "").trim().ifEmpty { null }

        private fun string(resources: Resources, id: Int, name: String): String =
            property(name) ?: resources.getString(id)

        private fun int(resources: Resources, id: Int, name: String): Int {
            val fromProperty = property(name) ?: return resources.getInteger(id)
            return fromProperty.toIntOrNull() ?: run {
                Log.w(TAG, "[retro] $PROPERTY_PREFIX$name='$fromProperty' is not a number, ignored")
                resources.getInteger(id)
            }
        }

        private fun percent(resources: Resources, id: Int, name: String): Float =
            int(resources, id, name).coerceIn(0, 100) / 100f

        private fun bool(resources: Resources, id: Int, name: String): Boolean {
            val fromProperty = property(name) ?: return resources.getBoolean(id)
            return when (fromProperty.lowercase()) {
                "true", "1", "on" -> true
                "false", "0", "off" -> false
                else -> {
                    Log.w(TAG, "[retro] $PROPERTY_PREFIX$name='$fromProperty' is not a boolean, ignored")
                    resources.getBoolean(id)
                }
            }
        }
    }
}
