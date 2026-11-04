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

import android.graphics.PointF
import android.graphics.Rect
import com.android.car.scalableui.model.Corner
import android.util.Log
import android.view.SurfaceControl

/** One task the barrel bends: its panel, its leash and where its variant put it on the display. */
/** [corner] is the panel's own corner radius: the cover takes it, so a rounded task keeps its corners. */
class BarrelTarget(val panelId: String, val leash: SurfaceControl, val bounds: Rect, val corner: Corner)

/**
 * The barrel of the curved-crt look, built without the task's pixels: the extension warps the
 * sampling coordinate by `point * (1 + 0.028 * intensity * point.yx^2)`; this class cuts each
 * target into horizontal strips, each a [SurfaceControl.mirrorSurface] of the target's leash,
 * and scales every strip horizontally by that factor at its own height. SurfaceFlinger then
 * reads the task's buffer once per strip with a different transform: no copy, no extra frame.
 *
 * Everything lives under the overlay panel's own surface, so the panel's variant (bounds, layer,
 * visibility) carries the whole stack:
 *
 *     overlay surface (DecorPanel, SurfaceControlViewHost)
 *       layer  0  the overlay view: scanlines, glass edge
 *       layer -1  N mirror strips per target, scaled per strip
 *       layer -2  an opaque black cover over each target
 *
 * All targets bend about one [center], the overlay's, so with several tasks under one
 * whole-display overlay the strips of neighbouring tasks line up into one glass.
 *
 * The strips and the cover are placed from the target's bounds, and a target's bounds move:
 * Scalable UI animates a panel by writing new bounds into it every frame and copying them to
 * the leash. [relayout] takes the same bounds and moves the existing strips and cover, so a
 * panel in transition keeps its barrel instead of jumping to the new place when the transition
 * ends. Only positions, scales and crops change; no surface is created or removed for a move.
 *
 * The covers hide the flat originals. They cannot be hidden with alpha 0 instead: SurfaceFlinger
 * drops input for buffer layers with alpha 0 (`LayerSnapshot::canReceiveInput`), and the real
 * tasks have to stay the touch targets. Mirrors carry `InputConfig::CLONE` and never take input.
 *
 * Mirroring the display area instead of each task is not an option: decor surfaces, this
 * overlay included, are children of the display area leash (AutoDecorManager.java:138), so a
 * mirror of it would contain its own mirrors.
 *
 * Half a barrel: strips bend the vertical edges, the horizontal ones stay straight. Vertical
 * scaling that varies with x would need a grid of tiles with visible seams between them.
 *
 * Coordinates: inside a mirror root the task's content starts at (0, 0), not at the task's
 * display position (measured on the emulator: buffer row 32 landed on display row 32 while the
 * real task had row 0 there). So crops are cut in task-local space and the mirror root is
 * positioned at the task's offset inside the overlay.
 *
 * [cover] false leaves the flat original visible under the mirrors: a diagnostic, to see whether
 * the mirrors draw anything at all on a given device.
 */
/**
 * @param fitTheTarget true when the look is on one rounded panel: the barrel then fits that
 *   panel whatever the fit knob says. Strips wider than the panel would carry its rounded
 *   corner past the panel's own arc, where nothing covers them, as a sliver of the flat app.
 */
class RetroBarrel(knobs: RetroKnobs, fitTheTarget: Boolean = false) {

    private val strips: Int = knobs.barrelStrips
    private val bulge: Float = knobs.barrelBulge
    private val cover: Boolean = knobs.barrelCover
    /**
     * True: the widest rows are scaled to exactly the task's width and the other rows shrink
     * instead, so nothing leaves the screen and the cover shows as black at the sides, like the
     * bezel of a real tube. False: the widest rows are stretched and cut off at the task's edge.
     */
    private val fit: Boolean = knobs.barrelFit || fitTheTarget
    /**
     * True: the middle rows are the widest and the rows shrink towards the top and bottom,
     * the way a tube bulges. False: the extension's formula, the edge rows are the widest.
     */
    private val outward: Boolean = knobs.barrelOutward

    /**
     * One mirror strip. [layoutStrip] sets where the barrel puts it; [jitter] adds [shiftX] on
     * top and a later layout keeps that shift, so a move and the shake never fight for the
     * position.
     */
    private class Strip(val mirror: SurfaceControl, val index: Int) {
        var baseX = 0f
        var baseY = 0f
        /** The strip's middle row as 0..1 of the overlay height, the shader's `uv.y`. */
        var centerY = 0f
        var shiftX = 0f
        /** Whether the strip has rows of the task under it; strips off the task are hidden. */
        var shown = false
    }

    /** One target's share of the barrel: its cover and all [strips] strips, shown or hidden. */
    private class Target(
        val panelId: String,
        val leash: SurfaceControl,
        /** The bounds the cover and strips are laid out for right now. */
        val bounds: Rect,
        /** The corner radius the cover is cut with right now. */
        var corner: Corner,
        val cover: SurfaceControl?,
        val strips: List<Strip>,
    )

    private val targets = ArrayList<Target>()
    private val overlayBounds = Rect()
    private val center = PointF()

    val isShown: Boolean
        get() = targets.isNotEmpty()

    /** The panels that have strips in this barrel. */
    val targetPanelIds: Set<String>
        get() = targets.mapTo(LinkedHashSet()) { target -> target.panelId }

    /** The leash the strips of [panelId] mirror, or null when the panel is not in the barrel. */
    fun leashOf(panelId: String): SurfaceControl? =
        targets.firstOrNull { target -> target.panelId == panelId }?.leash

    /**
     * @param parent the overlay panel's surface; [overlayBounds] is its position on the display
     * @param center the point the glass bulges about, in display coordinates
     */
    fun show(
        transaction: SurfaceControl.Transaction,
        parent: SurfaceControl,
        targets: List<BarrelTarget>,
        overlayBounds: Rect,
        center: PointF,
    ) {
        hide(transaction)
        if (strips == 0) {
            Log.i(TAG, "[retro] 0 strips: no barrel, no cover")
            return
        }
        this.overlayBounds.set(overlayBounds)
        this.center.set(center)
        for (target in targets) {
            val targetCover = if (cover) createCover(transaction, parent) else null
            // Every strip exists from the start, hidden until the task has rows under it: a
            // panel sliding in from off-screen gains strips row by row without new surfaces.
            // Hidden explicitly: a mirror root is created visible and without a crop (unlike a
            // Builder layer), so an unplaced strip would show the whole task at the overlay's
            // origin. Seen on the emulator as a second map in the top-left corner.
            val targetStrips = List(strips) { index ->
                val mirror = SurfaceControl.mirrorSurface(target.leash)
                transaction.reparent(mirror, parent).setLayer(mirror, STRIP_LAYER).hide(mirror)
                Strip(mirror, index)
            }
            val laidOut = Target(target.panelId, target.leash, Rect(target.bounds), target.corner,
                targetCover, targetStrips)
            this.targets.add(laidOut)
            layout(transaction, laidOut)
        }
        Log.i(TAG, "[retro] barrel shown: ${targets.size} targets x $strips strips, bulge=$bulge,"
            + " fit=$fit, outward=$outward, cover=$cover, overlay=$overlayBounds, center=$center")
    }

    /**
     * Moves the cover and strips of [panelId] to [bounds], the panel's bounds of this frame, and
     * cuts the cover with [corner], the panel's corner radius of this variant. Returns true when
     * something moved; false when the panel is not in the barrel or already sits at [bounds].
     */
    fun relayout(
        transaction: SurfaceControl.Transaction,
        panelId: String,
        bounds: Rect,
        corner: Corner,
    ): Boolean {
        val target = targets.firstOrNull { candidate -> candidate.panelId == panelId }
            ?: return false
        if (target.bounds == bounds && sameRadii(target.corner, corner)) {
            return false
        }
        target.bounds.set(bounds)
        target.corner = corner
        layout(transaction, target)
        return true
    }

    /** [Corner] has no equals; two are the same cover when all four radii match. */
    private fun sameRadii(first: Corner, second: Corner): Boolean =
        first.topLeftRadius == second.topLeftRadius &&
            first.topRightRadius == second.topRightRadius &&
            first.bottomLeftRadius == second.bottomLeftRadius &&
            first.bottomRightRadius == second.bottomRightRadius

    private fun createCover(
        transaction: SurfaceControl.Transaction,
        parent: SurfaceControl,
    ): SurfaceControl {
        val cover = SurfaceControl.Builder()
            .setName("retro-cover")
            .setColorLayer()
            .setParent(parent)
            .build()
        transaction.setColor(cover, floatArrayOf(0f, 0f, 0f))
            .setAlpha(cover, 1f)
            .setLayer(cover, COVER_LAYER)
        return cover
    }

    /** Puts [target]'s cover and strips where [Target.bounds] says; hides what has no rows. */
    private fun layout(transaction: SurfaceControl.Transaction, target: Target) {
        val taskBounds = target.bounds
        target.cover?.let { cover ->
            // An empty crop means "no crop" to SurfaceFlinger, so an empty task is hidden instead.
            if (taskBounds.isEmpty) {
                transaction.hide(cover)
            } else {
                // The task leash is rounded by its panel's <Corner> and the mirrors inherit it;
                // the cover must be cut the same way or a black ring shows in each corner. The
                // crop must be set for the radius to apply (AutoSurfaceTransaction says so too).
                val corner = target.corner
                transaction.setPosition(cover, (taskBounds.left - overlayBounds.left).toFloat(),
                    (taskBounds.top - overlayBounds.top).toFloat())
                    .setCrop(cover, Rect(0, 0, taskBounds.width(), taskBounds.height()))
                    .setCornerRadius(cover, corner.topLeftRadius.toFloat(),
                        corner.topRightRadius.toFloat(), corner.bottomLeftRadius.toFloat(),
                        corner.bottomRightRadius.toFloat())
                    .show(cover)
            }
        }
        val halfHeight = overlayBounds.height() / 2f
        for (strip in target.strips) {
            layoutStrip(transaction, target, strip, halfHeight)
        }
    }

    private fun layoutStrip(
        transaction: SurfaceControl.Transaction,
        target: Target,
        strip: Strip,
        halfHeight: Float,
    ) {
        val taskBounds = target.bounds
        // Strips are cut over the overlay's height (display rows) so every target shares the
        // same rows; the crop itself is in the task's own space, where its content starts at 0.
        val stripTop = overlayBounds.top + overlayBounds.height() * strip.index / strips
        val stripBottom = overlayBounds.top + overlayBounds.height() * (strip.index + 1) / strips
        val crop = Rect(0, maxOf(stripTop, taskBounds.top) - taskBounds.top,
            taskBounds.width(), minOf(stripBottom, taskBounds.bottom) - taskBounds.top)
        if (crop.isEmpty) {
            if (strip.shown) {
                transaction.hide(strip.mirror)
                strip.shown = false
            }
            return
        }
        // -1 at the top edge, 0 in the middle, +1 at the bottom edge, like point.y.
        val relativeY = ((stripTop + stripBottom) / 2f - center.y) / halfHeight
        val squared = relativeY * relativeY
        // Outward: widest in the middle (1 - y²); the extension: widest at the edges (y²).
        val stretch = 1f + bulge * (if (outward) 1f - squared else squared)
        // Fit: divide by the widest row's stretch (1 + bulge), so that row lands at 1.0.
        val scaleX = if (fit) stretch / (1f + bulge) else stretch

        // Put the task's local origin where the task is, then scale about the centre:
        // display x = center + scale * (task.left + local - center), minus the overlay origin.
        strip.baseX = center.x + scaleX * (taskBounds.left - center.x) - overlayBounds.left
        strip.baseY = (taskBounds.top - overlayBounds.top).toFloat()
        strip.centerY = ((stripTop + stripBottom) / 2f - overlayBounds.top) / overlayBounds.height()
        transaction.setPosition(strip.mirror, strip.baseX + strip.shiftX, strip.baseY)
            .setScale(strip.mirror, scaleX, 1f)
            .setCrop(strip.mirror, crop)
        if (!strip.shown) {
            transaction.show(strip.mirror)
            strip.shown = true
        }
    }

    /**
     * The VHS shake, one frame of it: every strip gets the extension's per-band jitter
     * (`(random(band, frame) - 0.5) * mix(1.8, 4.5, boost)` px) plus the tracking bar's pull
     * (`tracking * mix(5, 12, boost)` px) where the bar is, all scaled by intensity and
     * [amount]. Only positions change; SurfaceFlinger re-composes the same buffers.
     *
     * The extension's bands are 12 px; here a band is one strip, so more strips = finer shake.
     */
    fun jitter(transaction: SurfaceControl.Transaction, seconds: Float, amount: Float, intensity: Float) {
        if (targets.isEmpty() || amount <= 0f) {
            return
        }
        val boost = RetroNoise.smoothstep(0.55f, 1f, intensity)
        val frame = kotlin.math.floor(seconds * 30f)
        val bar = RetroNoise.trackingBar(seconds)
        for ((targetIndex, target) in targets.withIndex()) {
            for (strip in target.strips) {
                if (!strip.shown) {
                    continue
                }
                val band = (targetIndex * strips + strip.index).toFloat()
                val jitterPx = (RetroNoise.random(band, frame) - 0.5f) *
                    RetroNoise.mix(1.8f, 4.5f, boost)
                val trackingPx = RetroNoise.tracking(strip.centerY, bar) *
                    RetroNoise.mix(5f, 12f, boost)
                strip.shiftX = (jitterPx + trackingPx) * intensity * amount
                transaction.setPosition(strip.mirror, strip.baseX + strip.shiftX, strip.baseY)
            }
        }
    }

    fun hide(transaction: SurfaceControl.Transaction) {
        if (targets.isEmpty()) {
            return
        }
        for (target in targets) {
            for (strip in target.strips) {
                transaction.remove(strip.mirror)
            }
            target.cover?.let { cover -> transaction.remove(cover) }
        }
        targets.clear()
        Log.i(TAG, "[retro] barrel hidden")
    }

    companion object {
        private const val TAG = "RetroBarrel"
        private const val COVER_LAYER = -2
        private const val STRIP_LAYER = -1
    }
}
