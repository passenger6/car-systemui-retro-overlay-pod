# Working in this repository

This is a CarSystemUI **pod**: a self-contained `android_library` that CarSystemUI links in and
Scalable UI loads by class name from a layout's XML. Read `README.md` first; this file is what
an agent needs beyond it.

## Tools

| | |
|---|---|
| `tools/install.sh [SystemUI dir] [--check]` | the two wiring edits, idempotent; `--check` only reports |
| `tools/verify.sh [-s serial]` | read-only device check: overlay enabled for the right user, `[retro] tint` logged, cover and mirrors in SurfaceFlinger, no `NO_WINDOW`, no new `Binder buffer full`; one PASS/FAIL line each |
| `templates/retro_overlay.xml`, `templates/retro_overlay_controller.xml` | one-panel mode for another layout, `{{...}}` placeholders explained at the top of each file |

## How a pod plugs into CarSystemUI

1. `Android.bp` of the pod declares `android_library { name: "CarSystemUI-Retro-Overlay" }`
   with `CarSystemUI-Shared` as its only static dependency. CarSystemUI's own `Android.bp`
   lists the name in `carsysui_pods`, which is how the library gets onto the classpath.
2. `RetroPanelModule.kt` is a Dagger module. It binds `RetroController.Factory` into the
   decor-controller map (`@ClassKey(RetroController::class)`) and `RetroOverlayView` into the
   decor-view map (`@DecorPanelViewMap`). CarSystemUI's `PanelControllerModule` includes it.
3. Nothing in SystemUI calls the pod. A layout's `<Controller>` XML names the classes
   (`<ControllerName>com.android.systemui.car.retro.RetroController</ControllerName>`,
   `<View>…RetroOverlayView</View>`), Scalable UI looks them up in the two maps and builds the
   controller for that panel. A layout that does not name them never loads the pod.
4. The layout and every number live in an RRO (`samples/RetroOverlayRRO`), so a product adds
   the pod by overlaying resources, with no change to CarSystemUI beyond the two wiring edits.

Everything the pod does to the screen goes through `SurfaceControl.Transaction` on leashes that
Scalable UI already owns (`TaskPanel.leash`), plus the view of its own decor panel.

## Map of the pod

| File | Owns |
|---|---|
| `RetroController.kt` | lifecycle: watches its panel's variant (`StateManager.PanelStateObserver`), resolves targets (one panel via `<OverlayPanelId>`, else every task panel on the display), puts the look on / takes it off, follows transitions frame by frame |
| `RetroKnobs.kt` | one immutable snapshot of every setting: resources first, `debug.retro.<name>` on top; read each time the look goes on |
| `RetroPreset.kt` | the eight looks: `shaderId` (the branch in the shader), `curved`, `animated`, `tintRows` (the 3×3 matrix or `null`) |
| `RetroTint.kt` | `setColorTransform` on a task leash; the row-major → column-major transpose lives here |
| `RetroOverlayView.kt` | the AGSL shader (scanlines, mask, vignette, flicker, grain, tracking bar, glass edge) and the empty touchable region |
| `RetroBarrel.kt` | the mirror strips, the cover, the per-frame relayout and the shake |
| `RetroNoise.kt` | the shared clock, random and tracking-bar position, so shader and strips agree |
| `res/values/config.xml` | the knob defaults; `samples/…/res/values/config.xml` overrides them |

## Adding things

- **A preset**: one entry in `RetroPreset` (pick a new `shaderId`, give it `tintRows` or `null`),
  one branch on `preset` in the shader of `RetroOverlayView.kt` that sets `darken` and
  `lighten`, the name in the README table and in `retro-tour.sh` if you keep one. The tint is
  a matrix only: anything that needs a neighbouring pixel cannot be a preset here.
- **A knob**: a resource in `res/values/config.xml` with a comment, a field in `RetroKnobs`
  read through the same helper as its neighbours (so `debug.retro.<name>` works for free),
  the row in the README table. Percent knobs are 0..100 in XML and 0..1 in Kotlin.
- **Another target rule** (which panels get the look): `RetroController.targetTaskPanels()` is the one
  place; everything downstream takes a list of `TaskPanel`.
- **Another layout, one panel**: open the target task panel's XML and take four things: its
  `id`, its `displayId`, its `<Layer>` (+1 for the overlay), the `<Bounds>` of its visible
  variant (copy the element verbatim, dimension refs included). Fill `templates/retro_overlay.xml`
  and `templates/retro_overlay_controller.xml` with them, put both in the project's `res/xml/`,
  add `@xml/retro_overlay` to `window_states`. For every task panel that opens over the target
  (Settings in `app_panel` over the map) keep the pair of `paused` transitions on
  `_System_TaskOpenEvent` / `_System_TaskPanelEmptyEvent` with that panel id; the other
  `paused` transitions (SUW, user switch, keyguard) are the same in every layout.
- **Another layout, whole display**: `retro_screen.xml` + `retro_screen_controller.xml` from the
  sample as they are (no `<OverlayPanelId>`, bounds = the display); the controller finds every
  task panel on that display itself. Check the layout's task panel count against the strips
  ceiling below.

## Rules

- No change to `car-scalable-ui-lib`, `car-wm-shell-lib`, `frameworks/` or anything outside
  this directory except the two wiring edits in the README. If a feature needs one, stop and
  say so; do not reach for reflection or a workaround.
- Kotlin, full-word identifiers (no `dx`, `res`, `attrs`), comments say why, not what.
- Keep the `[retro]` log lines; they are the acceptance test on a device.
- Every number is a resource with a `debug.retro.*` twin; no constants in code for things a
  product would want to change.
- A17 only: the tree is `aosp-17_r1`; do not cite A16 sources.

## Invariants that are not obvious from the code

- **The cover is black, not alpha 0.** SurfaceFlinger drops input to a buffer layer at alpha 0,
  and hiding the leash hides its mirrors; the opaque cover is the only way to hide the flat
  original and keep the app tappable (`LayerSnapshot::canReceiveInput`).
- **Strips are bounded by window count, not by GPU.** Each mirror clones the task's input
  windows, and SurfaceFlinger sends every window of the display to system_server in one
  oneway binder call (~512 KB). Whole display at 48 strips overflows it and a panel stops
  taking taps after the next layout change (`NO_WINDOW` in `dumpsys input`). 24 is the measured
  ceiling on the DEWD landscape layout; the shake must stay 0 on the whole display.
- **Mirrors inherit the leash's rounded corners**, so the cover is cut with the target's
  `Corner` radii and, in one-panel mode, the shader is transparent beyond the corner.
- **Whole-display mode bends every strip about the display's centre**, so neighbouring tasks
  form one glass; one-panel mode bends about the panel.

## Where things are in `aosp-17_r1`

| | |
|---|---|
| wiring edits | `packages/apps/Car/SystemUI/Android.bp` (`carsysui_pods`), `packages/apps/Car/SystemUI/src/com/android/systemui/car/wm/scalableui/panel/controller/PanelControllerModule.java` |
| the reference layout the sample extends | `packages/apps/Car/SystemUI/samples/DEWDLand/` (`dewd-land-res-base`), its `res/xml/map_panel.xml` is the target in the sample |
| decor panel base class, task panel | `packages/apps/Car/SystemUI/src/com/android/systemui/car/wm/scalableui/view/DecorPanelControllerBase.java`, `…/panel/TaskPanel.java` |
| Scalable UI model and StateManager | `packages/apps/Car/systemlibs/car-scalable-ui-lib/src/com/android/car/scalableui/` |
| decor host window and surfaces | `packages/services/Car/libs/car-wm-shell-lib/src/com/android/wm/shell/automotive/AutoDecor.java`, `AutoDecorManager.java` |
| `mirrorSurface`, `setColorTransform` | `frameworks/base/core/java/android/view/SurfaceControl.java` |
| why alpha 0 loses input | `frameworks/native/services/surfaceflinger/FrontEnd/LayerSnapshot.cpp` (`canReceiveInput`) |
| the window-info binder call | `frameworks/native/services/surfaceflinger/WindowInfosListenerInvoker.cpp` |

API visibility: `SurfaceControl.mirrorSurface` and `Transaction.setColorTransform` are hidden,
platform-only (`@hide`); SystemUI may call them, an app may not. Everything from Scalable UI
the pod uses is its public controller surface. `RuntimeShader` is public API.

## Verifying a change

```
adb shell cmd statusbar carsysui-dispatch-event retro_off
adb shell cmd statusbar carsysui-dispatch-event retro_on
adb logcat -d | grep '\[retro\]'                      # tint … on …, frames started/stopped
adb shell dumpsys SurfaceFlinger | grep -c retro-     # cover + strips present
adb shell dumpsys input | grep NO_WINDOW              # must be empty
adb logcat -d | grep -c 'Binder buffer full'          # must not grow while idle
```

On an emulator run `adb shell service call SurfaceFlinger 1008 i32 1` first, or the tint is
invisible on the HWC path. Builds (`m CarSystemUI RetroOverlayRRO`), pushes and the SystemUI
restart are done by the person, not by the agent, unless they say otherwise.
