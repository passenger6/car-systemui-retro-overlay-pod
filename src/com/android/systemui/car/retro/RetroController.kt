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
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceControl
import android.view.View
import com.android.car.scalableui.loader.xml.parser.PanelControllerParser.OVERLAY_PANEL_ID_TAG
import com.android.car.scalableui.manager.StateManager
import com.android.car.scalableui.model.PanelControllerMetadata
import com.android.car.scalableui.model.PanelState
import com.android.car.scalableui.panel.DecorPanelController
import com.android.car.scalableui.panel.PanelPool
import com.android.systemui.car.wm.scalableui.panel.PanelUtils
import com.android.systemui.car.wm.scalableui.panel.TaskPanel
import com.android.systemui.car.wm.scalableui.panel.controller.DecorPanelViewMap
import com.android.systemui.car.wm.scalableui.view.DecorPanelControllerBase
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.shared.annotations.ShellMainThread
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import javax.inject.Provider

/**
 * Controller of the retro overlay panel. The panel XML owns when the look is on: this controller
 * watches its own panel's variant and, while it is visible, puts the preset on its targets: the
 * colour matrix on each task leash ([RetroTint]), the alpha overlay in its own view
 * ([RetroOverlayView]) and the barrel of mirrors under that view ([RetroBarrel]).
 *
 * Targets: the one task panel named by `<OverlayPanelId>` in the controller XML, or, when the
 * tag is absent, every task panel on the overlay's display (the whole-display mode; the overlay
 * panel's variant then spans the display). Other decor panels are left as they are.
 *
 * A target in transition keeps its barrel: StateManager reports the start of a transition
 * ([onBeforePanelStateChanged]) and its end ([onPanelStateChanged], the animation-end callback),
 * and in between Scalable UI writes the panel's bounds every frame and copies them to the leash.
 * The frame loop here reads the same bounds in the same frame and moves the strips and cover
 * along ([RetroBarrel.relayout]); the mirrors are built once and rebuilt only when the look
 * goes on or a target has no strips to move.
 *
 * Settings come from [RetroKnobs] (resources, an RRO per project, `debug.retro.*` properties on
 * top) and are read again every time the look is put on, so a property set between `retro_off`
 * and `retro_on` is what the on uses. The controller XML stays a plain `<Controller>`.
 */
class RetroController @AssistedInject constructor(
    @Assisted panelId: String,
    @Assisted metadata: PanelControllerMetadata,
    @DecorPanelViewMap decorPanelViewMap: Map<Class<*>, @JvmSuppressWildcards Provider<View>>,
    private val context: Context,
    private val panelUtils: PanelUtils,
    @ShellMainThread private val shellMainExecutor: ShellExecutor,
) : DecorPanelControllerBase(panelId, metadata, decorPanelViewMap), StateManager.PanelStateObserver {

    /** Null means the whole display. */
    private val targetPanelId: String? = metadata.getStringConfiguration(OVERLAY_PANEL_ID_TAG)
    private var overlayView: RetroOverlayView? = null

    // Shell thread from here on.
    /** The knobs of the look that is on now, or the last one that was. */
    private var knobs: RetroKnobs = RetroKnobs.read(context.resources)
    private var barrel = RetroBarrel(knobs)
    /** Targets in transition right now: their strips follow their bounds every frame. */
    private val followingPanelIds = LinkedHashSet<String>()
    /** The per-frame callback (follow + shake); null while nothing moves. */
    private var frames: Runnable? = null
    private var lastJitterFrameNanos = 0L
    /** The leashes the tint was put on, so they can be cleared even after their panels moved. */
    private val tintedLeashes = ArrayList<SurfaceControl>()

    init {
        Log.i(TAG, "[retro] $panelId: $knobs target=${targetPanelId ?: "whole display"}")
        if (targetPanelId != null) {
            StateManager.getInstance().addPanelStateObserver(this, panelId, targetPanelId)
        } else {
            // No ids: StateManager reports every panel, any task moving moves its strips.
            StateManager.getInstance().addPanelStateObserver(this)
        }
    }

    override fun getView(): View {
        val view = super.getView() as? RetroOverlayView
            ?: throw IllegalStateException("$mPanelId needs a RetroOverlayView, check <View>")
        overlayView = view
        // The bezel radius until the look goes on; applyOnShell sends the target's own.
        view.configure(knobs, -1f)
        return view
    }

    override fun onBeforePanelStateChanged(
        changedPanelIds: Set<String>,
        toPanelStates: Map<String, PanelState>,
    ) {
        // A transition starts: from here to onPanelStateChanged the changed panels get new
        // bounds every frame. Targets with strips follow; bars and other decor have none.
        shellMainExecutor.execute {
            if (!barrel.isShown) {
                return@execute
            }
            val targetIds = barrel.targetPanelIds
            val moving = changedPanelIds.filter { changedId -> changedId in targetIds }
            if (moving.isEmpty()) {
                return@execute
            }
            followingPanelIds.addAll(moving)
            logIfDebuggable("following $moving")
            startFrames()
        }
    }

    override fun onPanelStateChanged(
        changedPanelIds: Set<String>,
        toPanelStates: Map<String, PanelState>,
    ) {
        shellMainExecutor.execute {
            followingPanelIds.removeAll(changedPanelIds)
            val visible = panelUtils.getCurrentVariant(mPanelId)?.isVisible == true
            logIfDebuggable("state changed: $changedPanelIds visible=$visible")
            when {
                !visible -> releaseOnShell()
                // Our own variant changed (retro_on, the app's open event) or nothing is on yet:
                // the look is (re)built, knobs and all.
                mPanelId in changedPanelIds || tintedLeashes.isEmpty() -> applyLook()
                else -> settleTargets(changedPanelIds)
            }
        }
    }

    override fun destroy() {
        StateManager.getInstance().removePanelStateObserver(this)
        shellMainExecutor.execute { releaseOnShell() }
        super.destroy()
    }

    private fun targetTaskPanels(): List<TaskPanel> {
        if (targetPanelId != null) {
            return listOfNotNull(panelUtils.getTaskPanel { panel -> panel.panelId == targetPanelId })
        }
        val displayId = panelUtils.getDecorPanel { panel -> panel.panelId == mPanelId }?.displayId
        if (displayId == null) {
            Log.w(TAG, "[retro] $mPanelId is not in the panel pool, no display to cover")
            return emptyList()
        }
        return PanelPool.getInstance()
            .getPanels { panel -> panel is TaskPanel && panel.displayId == displayId }
            .filterIsInstance<TaskPanel>()
    }

    /** One-panel mode on a panel with a corner radius: the barrel must stay inside it. */
    private fun singleRoundedTarget(): Boolean {
        if (targetPanelId == null) {
            return false
        }
        val corner = panelUtils.getTaskPanel { panel -> panel.panelId == targetPanelId }
            ?.cornerRadius ?: return false
        return maxOf(corner.topLeftRadius, corner.topRightRadius, corner.bottomLeftRadius,
            corner.bottomRightRadius) > 0
    }

    private fun applyLook() {
        val view = overlayView
        if (view == null) {
            Log.w(TAG, "[retro] $mPanelId visible before its view exists, skipping")
            return
        }
        // The overlay surface belongs to the view's thread; the leash work belongs to the shell.
        view.post {
            val overlaySurface = view.viewRootImpl?.surfaceControl
            shellMainExecutor.execute { applyOnShell(view, overlaySurface) }
        }
    }

    private fun applyOnShell(view: RetroOverlayView, overlaySurface: SurfaceControl?) {
        val transaction = SurfaceControl.Transaction()
        stopFrames()
        clearTint(transaction)
        barrel.hide(transaction)

        // Fresh knobs every time: this is what makes `setprop debug.retro.*` + off/on work.
        knobs = RetroKnobs.read(context.resources)
        barrel = RetroBarrel(knobs, fitTheTarget = singleRoundedTarget())
        val targets = ArrayList<BarrelTarget>()
        for (taskPanel in targetTaskPanels()) {
            val leash = taskPanel.leash
            if (leash == null || !leash.isValid) {
                Log.w(TAG, "[retro] ${taskPanel.panelId} has no leash yet, skipped")
                continue
            }
            RetroTint.apply(transaction, leash, knobs.preset, knobs.intensity, knobs.monochrome)
            tintedLeashes.add(leash)
            Log.i(TAG, "[retro] tint ${knobs.preset.xmlName} on ${taskPanel.panelId}")
            // The panel's own bounds: what the leash has now, mid-transition included.
            targets.add(BarrelTarget(taskPanel.panelId, leash, Rect(taskPanel.bounds),
                taskPanel.cornerRadius))
        }

        // The glass edge of the overlay follows the target's own corner when there is one
        // target (the look on one rounded panel); over the whole display it keeps its own
        // bezel radius, signalled by a negative value.
        val glassRadiusPx = if (targetPanelId != null) {
            targets.firstOrNull()?.corner?.let { corner ->
                maxOf(corner.topLeftRadius, corner.topRightRadius, corner.bottomLeftRadius,
                    corner.bottomRightRadius).toFloat()
            } ?: -1f
        } else {
            -1f
        }
        val knobsForView = knobs
        // The shader's uniforms belong to the view's thread, like its drawing.
        view.post { view.configure(knobsForView, glassRadiusPx) }

        if (knobs.wantsBarrel) {
            val overlayBounds = panelUtils.getCurrentVariant(mPanelId)?.bounds
            when {
                targets.isEmpty() ->
                    Log.w(TAG, "[retro] no targets with a leash, barrel skipped this time")
                overlaySurface == null || !overlaySurface.isValid ->
                    Log.w(TAG, "[retro] overlay surface not ready, barrel skipped this time")
                overlayBounds == null ->
                    Log.w(TAG, "[retro] $mPanelId has no variant bounds, barrel skipped this time")
                else -> barrel.show(transaction, overlaySurface, targets, Rect(overlayBounds),
                    PointF(overlayBounds.exactCenterX(), overlayBounds.exactCenterY()))
            }
        }
        transaction.apply()
        startFrames()
    }

    /**
     * A transition of other panels ended while the look stays on. Targets that have strips are
     * moved to their final bounds; a target without strips to move (it had no leash when the
     * look went on, or has a new root task leash since) sends the look through [applyLook],
     * the one path that creates surfaces.
     */
    private fun settleTargets(changedPanelIds: Set<String>) {
        val transaction = SurfaceControl.Transaction()
        var moved = false
        for (taskPanel in targetTaskPanels()) {
            if (taskPanel.panelId !in changedPanelIds) {
                continue
            }
            val leash = taskPanel.leash
            val tinted = leash != null && leash.isValid &&
                tintedLeashes.any { applied -> applied === leash }
            val mirrored = !knobs.wantsBarrel || barrel.leashOf(taskPanel.panelId) === leash
            if (!tinted || !mirrored) {
                Log.i(TAG, "[retro] ${taskPanel.panelId} changed without a look on its leash"
                    + " (tinted=$tinted mirrored=$mirrored), rebuilding")
                applyLook()
                return
            }
            if (barrel.relayout(transaction, taskPanel.panelId, taskPanel.bounds,
                    taskPanel.cornerRadius)) {
                moved = true
            }
        }
        if (moved) {
            transaction.apply()
        }
        logIfDebuggable("settled $changedPanelIds moved=$moved")
        stopFramesIfIdle()
    }

    private fun releaseOnShell() {
        stopFrames()
        followingPanelIds.clear()
        val transaction = SurfaceControl.Transaction()
        clearTint(transaction)
        barrel.hide(transaction)
        transaction.apply()
    }

    /**
     * One commit-stage Choreographer callback per frame while the barrel has work: the strips
     * of targets in transition follow their bounds, and the shake moves all strips 30 times a
     * second. Commit stage, not a frame callback: Scalable UI's animators run in the animation
     * stage of the same frame and write the panels' bounds there, so reading the bounds after
     * it keeps the cover on the leash in every frame instead of one frame behind.
     */
    private fun startFrames() {
        if (frames != null || !hasFrameWork()) {
            return
        }
        val callback = object : Runnable {
            override fun run() {
                if (frames !== this) {
                    return
                }
                onFrame()
                if (frames === this) {
                    Choreographer.getInstance().postCallback(Choreographer.CALLBACK_COMMIT, this, null)
                }
            }
        }
        frames = callback
        Choreographer.getInstance().postCallback(Choreographer.CALLBACK_COMMIT, callback, null)
        Log.i(TAG, "[retro] frames started: shake=${knobs.jitter} following=$followingPanelIds")
    }

    private fun onFrame() {
        val transaction = SurfaceControl.Transaction()
        var dirty = false
        for (panelId in followingPanelIds) {
            val taskPanel = panelUtils.getTaskPanel { panel -> panel.panelId == panelId } ?: continue
            // Mid-transition the variant's corner changes with its bounds (Variant.java:153).
            if (barrel.relayout(transaction, panelId, taskPanel.bounds, taskPanel.cornerRadius)) {
                dirty = true
            }
        }
        val now = System.nanoTime()
        if (knobs.jitter > 0f && now - lastJitterFrameNanos >= JITTER_FRAME_NANOS) {
            lastJitterFrameNanos = now
            barrel.jitter(transaction, RetroNoise.seconds(), knobs.jitter, knobs.intensity)
            dirty = true
        }
        if (dirty) {
            transaction.apply()
        }
        stopFramesIfIdle()
    }

    private fun hasFrameWork(): Boolean =
        barrel.isShown && (knobs.jitter > 0f || followingPanelIds.isNotEmpty())

    private fun stopFramesIfIdle() {
        if (!hasFrameWork()) {
            stopFrames()
        }
    }

    private fun stopFrames() {
        val callback = frames ?: return
        Choreographer.getInstance().removeCallbacks(Choreographer.CALLBACK_COMMIT, callback, null)
        frames = null
        Log.i(TAG, "[retro] frames stopped")
    }

    private fun clearTint(transaction: SurfaceControl.Transaction) {
        for (leash in tintedLeashes) {
            if (leash.isValid) {
                RetroTint.clear(transaction, leash)
            }
        }
        if (tintedLeashes.isNotEmpty()) {
            Log.i(TAG, "[retro] tint cleared on ${tintedLeashes.size} leash(es)")
        }
        tintedLeashes.clear()
    }

    @AssistedFactory
    interface Factory : DecorPanelController.Factory<RetroController> {
        override fun create(panelId: String, metadata: PanelControllerMetadata): RetroController
    }

    companion object {
        private const val TAG = "RetroController"

        /** The extension's `floor(time * 30.0)`: the shake picks a new frame 30 times a second. */
        private const val JITTER_FRAME_NANOS = 1_000_000_000L / 30
    }
}
