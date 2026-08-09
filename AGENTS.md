# Onyx Boox Eink Optimization & SDK Guide for SudokuEink

This document provides context for AI agents working on this project regarding e-ink specific optimizations and Onyx SDK integration.

## System Navigation Bar and Edge-to-Edge Layout
In `EinkOptimizations.kt`, we use `WindowCompat.setDecorFitsSystemWindows(window, false)` to disable standard transition animations and prepare the window for optimal e-ink redrawing. 
**Important Note:** Because this draws the app edge-to-edge (behind system bars), the main Compose layout (such as the root `Surface` in `MainActivity.kt`) **MUST** use `Modifier.systemBarsPadding()` or `Modifier.navigationBarsPadding()` so that bottom buttons and interactions do not overlap with the system navigation bar.

## Stylus (Pen) Support
Standard Android `MotionEvent.TOOL_TYPE_STYLUS` and Jetpack Compose's `detectDragGestures` might not provide the best experience on Onyx Boox devices due to system-level optimizations (Scribble mode) that intercept strokes for zero-latency e-ink rendering.

To support the stylus properly:
1. **Onyx SDK Repository:** The `settings.gradle.kts` file includes Onyx maven repositories (`http://repo.boox.com/repository/proxy-public/`). Due to the HTTP endpoints, `isAllowInsecureProtocol = true` must be kept.
2. **Onyx SDK Dependency:** `com.onyx.android.sdk:onyxsdk-pen` is included in `app/build.gradle.kts`.
3. **Drawing Canvas (`DrawingCanvas.kt`):** Instead of pure Compose `Canvas`, we use an `AndroidView` wrapping a `SurfaceView`.
   - The `com.onyx.android.sdk.pen.TouchHelper` is attached to this `SurfaceView`.
   - Raw stylus paths are intercepted by the `RawInputCallback`.
   - The SDK natively renders the strokes directly to the screen (`setRawDrawingRenderEnabled(true)`) with no perceptible lag.
   - For image processing (like `DigitRecognizer`), we collect `TouchPointList` events, rebuild standard Android `Path`s, and draw them to a `Bitmap`.

### Eraser Integration & Gestures
1. **Onyx Hardware Eraser:** The physical eraser button (which triggers `TOOL_TYPE_ERASER` or `BUTTON_SECONDARY`) is aggressively intercepted by the `TouchHelper` when raw drawing is enabled. Standard `MotionEvent` listeners on the `SurfaceView` will not fire reliably. You **must** override `onBeginRawErasing` and `onEndRawErasing` inside `RawInputCallback` to detect and handle hardware eraser events.
2. **Scribble Gestures:** When implementing geometric erase heuristics (like zig-zag scribbling or striking out a digit), users frequently lift the stylus, producing multiple rapid, disconnected strokes. Your heuristic algorithms must aggregate bounding boxes (`maxX - minX`) and properties (like directional reversals) across **all strokes** captured within the timeout window, rather than evaluating strokes individually.
3. **Micro-Strokes Filter:** Small, incidental screen taps (e.g., bounding boxes `< 20px`) should be explicitly filtered out before running heavy ML inference.
4. **Clearing Ink — ☠ do NOT toggle `setRawDrawingEnabled`.** An earlier version of this
   app cleared ink by briefly toggling `touchHelper?.setRawDrawingEnabled(false)` then
   `true`. **Do not reintroduce this.** Two confirmed problems, ported over from
   NonogramEink's freeze investigation (`NonogramEink/NonogramEink/AGENTS.md` →
   "System-wide ANR / device freeze", incidents #1–3) after this app hit the same class of
   bug:
   - **First-stroke-after-flush ink loss.** The toggle calls `leaveScribbleMode` plus a
     pen-state transition under the hood; re-entering scribble mode happens lazily at the
     *next* pen-down, so the very next stroke drawn after any flush can lose its start.
   - **EPD-driver freeze trigger.** Toggling `setRawDrawingEnabled` while the pen is
     physically on the panel can wedge the Onyx `onyx_epdc` kernel driver in an
     uninterruptible wait and freeze the entire device (not just this app) — confirmed via
     live WCHAN capture on a Boox Max3. The freeze is triggered by *any* driver-mode
     transition racing a pen-down; the toggle is exactly such a transition, and this app's
     original eraser `ACTION_DOWN` handler toggled while the pen was still touching the
     panel — the worst case of this race, not merely a rare one.

   **What to do instead:** see "Raw-ink flush pipeline" below — `InkFlushController`
   clears ink via a repaint bracket (`EpdController.enablePost` + `HAND_WRITING_REPAINT_MODE`)
   that performs no driver-mode transition, gated so it never fires while `isPenDown`.
5. **E-ink Hardware Erase Conflict:** When the Onyx system native "Side Button Eraser" is enabled, the hardware performs a highly-optimized local refresh to erase strokes natively. If Android Compose updates its state (e.g., removing a digit badge) at the exact same millisecond that this native hardware clear occurs, the E-Ink controller will drop the Android UI `invalidate()` update because it is busy with the hardware layer. This results in the app's internal state clearing the digit, but the digit physically remaining on the screen. **Fixes required:** 
   - **Debouncing:** Onyx devices may rapidly fire multiple `onEndRawErasing` events for a single physical tap. You must **debounce** the Compose state updates (e.g., `onClearCell`) per cell by ~600ms. This ensures Jetpack Compose only invalidates the UI exactly once, precisely 600ms after the *final* hardware erase finishes, guaranteeing the e-ink screen is idle and will accept the UI refresh.
   - **Stale Lambda Capture (`rememberUpdatedState`):** Because the `TouchHelper` and its listeners (`RawInputCallback`, `onTouchListener`) are initialized only once in the `AndroidView`'s `update` block, they will permanently capture the `onClearCell` lambda from the very first composition. If the user starts a "New Game", `boardState` is re-created, and the captured lambda will silently modify the *discarded* `boardState` instead of the active one. Always wrap all functional parameters passed to `AndroidView` callbacks in `rememberUpdatedState`.

## Raw-ink flush pipeline (`InkFlushController.kt`)

`InlineDrawingCanvas` no longer clears ink inline at each call site. A single
`InkFlushController` (owned per canvas instance, `remember`ed in the composable) is the
only thing that flushes ink, and it is the only thing allowed to call the EPD/repaint
APIs. This mirrors NonogramEink's trailing render/flush pipeline
(`NonogramEink/NonogramEink/app/.../NonogramGridView.kt`), adapted for a `SurfaceView`
host instead of a plain custom `View`.

1. **The repaint bracket, not a toggle.** `flushNow()` clears ink by wrapping the
   `SurfaceView`'s canvas lock/draw/post in
   `EpdController.setViewDefaultUpdateMode(surfaceView, UpdateMode.HAND_WRITING_REPAINT_MODE)`
   → `holder.lockCanvas()` → redraw the persisted strokes → `EpdController.enablePost(surfaceView, 1)`
   **immediately before** `holder.unlockCanvasAndPost(canvas)` → `EpdController.resetViewUpdateMode(surfaceView)`.
   This is the same pattern the Onyx SDK's own scribble demos use
   (`OnyxAndroidDemo/.../scribble/request/PartialRefreshRequest.java`,
   `RendererToScreenRequest.java`) — it clears the raw-ink overlay and redraws committed
   content in one refresh, with no scribble-mode exit and no pen-state transition.
2. **`enablePost` must run before every post that should be visible during a pen
   session.** From the first raw pen-down, the EPD driver stops *posting* window updates
   to the panel at all — the window renders correctly into its buffer, but nothing reaches
   the display — until `enablePost` re-enables it. Omitting it silently drops that
   specific repaint; the strokes still "exist" in the SurfaceView's buffer, they just
   never appear until the next flush that does call it.
3. **Never flush while the pen is down.** `flushNow()` bails immediately if
   `isPenDown == true`. `isPenDown`/`isPenActive` are `@Volatile` and are the *only* state
   `RawInputCallback` methods write directly — see "Threading discipline" below.
4. **Scheduling, not immediate execution.** `scheduleFlush(reason, delayMs)` posts a
   single `Runnable` on the `SurfaceView` (cancelling any pending one first);
   `cancelPendingFlush(reason)` removes it. Every discard/commit path in the recognition
   `LaunchedEffect`, both raw-channel `end*` callbacks, and the stylus `MotionEvent`
   handlers call one of these — see the call sites in `InlineDrawingCanvas.kt` for the
   full list of reasons (`"fixedCellDiscard"`, `"recognitionCommit"`, `"endErase"`, etc.).
   Two delays are used: `COMMIT_FLUSH_DELAY_MS` (50 ms, after a definite content change)
   and `PEN_UP_SAFETY_DELAY_MS` (1000 ms, armed unconditionally on every raw pen-up as a
   structural guarantee that no discard path can strand ink or leave the EPD post-lock
   engaged — it sits above the 500 ms recognition timeout so the normal path's own
   shorter-delay reschedule always wins the race).
5. **Threading discipline.** `RawInputCallback` methods run on the Onyx SDK's private
   background executor, never the main thread. They may only: copy primitive point data,
   flip `controller.isPenDown`/`isPenActive`, and call `scheduleFlush`/`cancelPendingFlush`
   (safe from any thread — they just `post`/`removeCallbacks` on a `View`). Anything that
   touches Compose state (`paths`, `inkStrokes`, `onClearCell`, etc.) is wrapped in
   `view.post { }`. Do not regress this into direct state mutation on the callback thread.
6. **Second re-arm channel (stylus `MotionEvent`s).** The stylus is double-dispatched —
   both the raw SDK channel and `onTouchEvent` fire for the same physical stroke. A stray
   `ACTION_DOWN` the raw channel discards as noise could otherwise cancel a pending flush
   with no `onEndRaw*` ever following to re-arm it (NonogramEink hit this for real — see
   its AGENTS.md "Stranded stroke" entry). `onTouchEvent`'s stylus/eraser branch
   independently cancels on `ACTION_DOWN` and re-arms on `ACTION_UP`/`ACTION_CANCEL`
   (guarded by `!isPenDown` so it never fights a raw stroke actually in progress).
7. **Diagnostics.** The whole arm/cancel/flush lifecycle logs under **`SudoFlush`**
   (`adb logcat -s SudoFlush`), reason-tagged the same way as NonogramEink's `NonoFlush`.
   No flush should ever log while a preceding `beginDraw`/`beginErase` hasn't yet been
   followed by its matching `endDraw`/`endErase`.

For the full freeze root-cause history this pipeline defends against (kernel-driver
deadlock, refresh-mode aggravators, forensic signatures), see
`NonogramEink/NonogramEink/AGENTS.md` → "System-wide ANR / device freeze". That doc also
documents `EpdController.getAppScopeRefreshMode()` (device-verified readable/live on a
Boox Max3) — `MainScreen.kt`'s refresh-mode warning banner uses it via
`EinkOptimizations.isNonNormalRefreshMode()`.

## Non-Onyx (LCD) Device Support — DeviceCaps + androidx.ink

The app runs unmodified on non-Onyx stylus tablets, with the pen pipeline swapped out
behind **`DeviceCaps.isOnyx`**. Device-verified on a Teclast Airpad Pro (Android 15,
non-EMR stylus). Ported directly from NonogramEink's non-Onyx support
(`NonogramEink/NonogramEink/AGENTS.md` → "Non-Onyx (LCD) Device Support"), adapted to
Sudoku's whole-cell erase and HTR-recognition flow.

### Why no `TouchHelper` at all on non-Onyx
The Onyx SDK's `TouchHelper` **captures input on non-Onyx devices too**, through an
undocumented fallback — but it also **eats the view's stylus AND finger `MotionEvent`s**.
On this app that broke two things at once: no wet ink ever rendered (Onyx ink is drawn by
the EPD driver; an LCD has none), and every action-first button (digit/erase/notes) went
dead, because they depend on a **finger tap on a cell** (`SudokuBoard`'s
`combinedClickable`) which the fallback silently consumed — even with the pencil button off
(that only flips `setRawDrawingRenderEnabled`, not `setRawDrawingEnabled`). **Decision: on
non-Onyx, never create the `TouchHelper`.** `InlineDrawingCanvas`'s layout listener checks
`DeviceCaps.isOnyx` before building it; everything below replaces it.

### Software ink path (androidx.ink 1.0.0 stable)
- **Deps** (`app/build.gradle.kts`): `androidx.ink:ink-authoring:1.0.0`, `ink-brush`,
  `ink-strokes`, plus `androidx.input:input-motionprediction:1.0.0`. Don't downgrade to the
  alphas (API renames: `StockBrushes` members became functions like `pressurePen()`).
- **Two-layer overlay** (`InlineDrawingCanvas`'s `factory`, non-Onyx branch): a
  `FrameLayout` holding a plain [`PersistedInkView`](PersistedInkView.kt) (committed
  strokes only, drawn via the same `drawInkStrokes` routine the Onyx repaint bracket uses —
  see `InkFlushController.kt`) underneath an `InProgressStrokesView` (wet ink, low-latency
  front-buffered). **Not** a second `SurfaceView` for the committed layer: the overlay is
  itself a front-buffered `SurfaceView`, and two "on top" surfaces in one window have no
  defined relative Z-order — a plain `View` avoids that entirely.
- **Capture**: `handleSoftwareStylusEvent()` in `InlineDrawingCanvas.kt`, wired as the
  `FrameLayout`'s `setOnTouchListener` — with historical points (`getHistorical*`), so the
  digit recognizer sees Onyx-comparable point density. `requestUnbufferedDispatch` on DOWN.
  Finger events return `false` immediately so they fall through to `SudokuBoard`.
- **Lifecycle maps onto the Onyx `RawInputCallback`** it replaces, so the shared flush and
  recognition pipeline (`InkFlushController`, the `LaunchedEffect(lastStrokeTime)`
  recognition timeout, `isEraseGesture` heuristics) runs unchanged on both paths:

  | Onyx raw channel | Software path |
  |---|---|
  | `onBeginRawDrawing` | `ACTION_DOWN` (flags + cancel pending flush + start overlay stroke) |
  | `onRaw*TouchPointListReceived` | points accumulated per-MOVE, appended to `paths` at UP |
  | `onEndRawDrawing` → arms `lastStrokeTime` | `ACTION_UP` → same |
  | EPD driver renders wet ink | `InProgressStrokesView` renders wet ink |
  | flush = `enablePost` + `HAND_WRITING_REPAINT_MODE` repaint | flush = `persistedView.invalidate()` then, `OVERLAY_CLEAR_DELAY_MS` later, `removeFinishedStrokes` (`InkFlushController.flushSoftware`) |

  Finished strokes stay rendered in the overlay (androidx.ink keeps them until
  `removeFinishedStrokes`) — same "ink stays up during a fast burst, one commit shortly
  after the last pen-up" UX on both device families.
- **Whole-cell erase** (barrel button / eraser tool) is handled entirely inside
  `handleSoftwareStylusEvent`, mirroring the Onyx `onBeginRawErasing`/`onEndRawErasing` pair
  (clear the targeted cell's `inkStrokes` entry + debounced `onClearCell`, no ink ever
  drawn) rather than NonogramEink's per-tile toggle — that heuristic gesture erase (strike-
  through/scribble/X over a pencil-filled cell) is a *separate*, already-shared mechanism
  (`isEraseGesture`, evaluated in the recognition `LaunchedEffect`) that needs no non-Onyx
  special-casing at all, as long as regular (non-eraser) strokes populate `paths` normally.
  - ☠ **Do NOT decide erasing-ness only at `ACTION_DOWN`.** The himax driver on this pen can
    report a button held *before* touch-down as `buttonState=0` in the DOWN event, with the
    flag appearing only in the first `ACTION_MOVE` a few ms later (device-verified via
    NonogramEink's `NonoStylus` logs on this same pen). The check therefore re-runs on every
    MOVE as an **upgrade-only** flip (never downgraded once true) — any wet ink already
    drawn before the upgrade is cancelled via `inkOverlay.cancelStroke`, since Onyx's raw
    erase channel never draws ink either.
- **Diagnostics**: `adb logcat -s SudoStylus` — one line per DOWN/UP/CANCEL (MOVE stays
  silent to avoid flooding), tagged with `action`/`toolType`/`buttonState`/`pressure`, plus
  a one-shot `DeviceCaps.isOnyx` line at canvas init.

### What else is gated on `DeviceCaps.isOnyx`
- `InkFlushController`: `onyxSurface`/`persistedView`+`inkOverlay` are mutually exclusive
  (only one pair is ever populated), and `flushNow`/`redrawStrokes` branch on
  `DeviceCaps.isOnyx` to pick the EPD repaint bracket vs. the software equivalent.
  `EpdController` is never called on non-Onyx (every call there is a silent no-op — "it
  didn't throw" is not "it worked").
- `EinkOptimizations.isNonNormalRefreshMode()` (Onyx refresh-mode warning banner) already
  short-circuits to `false` on non-Onyx via its own manufacturer check.

### Known benign log noise (MediaTek)
`surfaceflinger E BufferQueueDump ... logFpsState_onCheckFps` spam appears once the ink
overlay exists — it's MTK's FPS-Go vendor framework dumping per-layer state for the
overlay's SurfaceView at Error severity (any SurfaceView app does this on this chipset).
Harmless; only investigate if it's accompanied by an actual rendering symptom (missing
ink, flicker, black rectangles). `PowerHalMgrImpl` info lines are the same framework
reacting to render activity. Same noise NonogramEink documented for this device family.

## Handwriting Persistence & Undo/Redo State
When maintaining handwriting strokes (`inkStrokes`) across game sessions and `Undo`/`Redo` actions:
1. **Source of Truth:** Since the Onyx `TouchHelper` only manages the ephemeral native e-ink layer, `inkStrokes` (a `Map` of cell coordinates to lists of `DrawingPoint`s) must be hoisted to the main Compose `GameScreen`. This state is re-applied to the canvas using Compose's standard `Canvas` or `Path` drawing to ensure strokes persist when the native hardware layer is flushed or when the app restarts.
2. **Synchronized History:** The `inkStrokes` map must be pushed into its own `SnapshotStateList` history (e.g., `inkStrokesHistory`) perfectly synchronized with `moveHistory` during any board update. 
3. **Asynchronous Stale State (Ghost Restores):** When utilizing `LaunchedEffect` to wait for a stroke debounce timeout before recognizing a digit, the `LaunchedEffect` captures the `inkStrokes` value at the moment the stroke was drawn. If the user clears the cell with the eraser *before* the timeout completes, the asynchronous block will finish and append its recognized result to the **stale** `inkStrokes` map it captured, effectively "restoring" handwriting that was just deleted. You **must** read `currentInkStrokes` via `rememberUpdatedState` directly inside the asynchronous block right before modifying it.
4. **Duplicate Eraser Events:** Never allow Android's `onTouchEvent` handling of `TOOL_TYPE_ERASER` to redundantly clear game state alongside the Onyx SDK's `RawInputCallback.onEndRawErasing`. This double-firing will push duplicate empty states into `moveHistory`, which causes the `Undo` button to pop an identical state and appear "broken" (doing nothing). Let the debounced `onEndRawErasing` handle the state updates.
4a. **Reset clears ink by design.** `GameScreen`'s Reset buttons set `inkStrokes = emptyMap()`, not `inkStrokes = initialStrokes`. `initialStrokes` holds whatever handwriting was in the save file at screen entry — reassigning it on Reset silently *restores* old ink instead of clearing it, which also defeats the point of Reset returning to the untouched puzzle (fixed clues only), exactly what `initialBoard` already does to `boardState`. `initialStrokes` still does its real job: seeding `inkStrokes` once, on mount, from a saved game.
5. **State Synchronization between Button Inputs and Handwriting:** When overriding or clearing a cell using UI action buttons (digit entry or eraser) that was previously populated by handwritten input, you must explicitly reset its `isPencil` state to `false`. Otherwise, the cell will get stuck rendering the new digit using the multi-badge pencil layout rather than the standard centered text layout.
6. **Pencil button gates input, NOT the overlay — never unmount `InlineDrawingCanvas` to "turn the pen off".** The persisted-stroke overlay *is* that composable's `SurfaceView` (repainted by `InkFlushController`'s own canvas draws). An earlier version wrapped the call site in `if (isPencilMode) { InlineDrawingCanvas(...) }`, so toggling the pencil button off unmounted the surface and made already-committed handwriting vanish (it reappeared on re-enable only because `surfaceCreated` redraws the strokes). **Do not reintroduce that wrapper.** Keep the canvas mounted unconditionally and pass the button state through as `penInputEnabled` instead:
   - **Disabling input** = flip native rendering off with `TouchHelper.setRawDrawingRenderEnabled(penInputEnabled)` (in a `LaunchedEffect(penInputEnabled, touchHelper)`) **plus** gating the `RawInputCallback`/`onTouchListener` bodies on `currentPenInputEnabled` (via `rememberUpdatedState`, same discipline as the other lambda params). This is the render flag, **not** the banned `setRawDrawingEnabled` mode toggle (see "Clearing Ink" above): it does no scribble-mode/pen-state transition, and it only ever fires from the button — never mid-stroke — so it can't race a pen-down.
   - **Keep the flush lifecycle intact even while disabled.** The `begin*`/`end*` callbacks still flip `isPenDown`/`isPenActive` and `scheduleFlush` exactly as when enabled; only stroke capture (`paths` append), recognition (`lastStrokeTime`), and erase-commit are gated out. This guarantees no disabled-pen touch can strand the EPD post-lock.
   - **The `onTouchListener` returns `false` immediately when disabled**, so finger taps fall through to the Sudoku grid below (cell selection / auto-notes long-press keep working) and no eraser/re-arm logic runs.

## Refreshes
For manual full-screen refreshes to clear ghosting, `EinkOptimizations.forceFullRefresh(view)` can be called. Use this sparingly but consistently after dialog dismissals or major scene changes where e-ink ghosting is prominent.

## Machine Learning Models (Digit Recognition)
The app evaluates handwritten digit recognition through custom ML models. 
- **Dependencies:** We use `com.microsoft.onnxruntime:onnxruntime-android` for ONNX inference and `org.tensorflow:tensorflow-lite` for TF models.
- **Model Routing & On-Demand Downloads:** Multiple HTR models (TFLite, ONNX, ML Kit) are supported. To save APK size, external models (like ONNX and ML Kit) are *not* bundled in assets or downloaded indiscriminately on startup. Instead, they are downloaded on-demand via setup dialogs in the Settings screen (managed by `ModelDownloadManager`). The `InlineDrawingCanvas` dynamically routes inference only to the actively selected model (via `SettingsManager.loadHtrModel`).
- **ONNX Implementation details:** When creating an `OnnxTensor` from raw pixel data in Kotlin/Java, a **Direct** `ByteBuffer` (e.g. `ByteBuffer.allocateDirect()`) must be used for native JNI compatibility. Allocating a standard heap `FloatBuffer` will result in silent runtime crashes or initialization errors.
- **ONNX Model Location:** The ONNX model is loaded from the local filesystem (`ModelDownloadManager.getOnnxModelFile(context)`) rather than from the APK `assets` folder.
- **Data Preprocessing:** Standard 0-255 scaling to 0.0-1.0 floats is required. Although models might be trained on EMNIST (which uses a transposed layout), inference against drawn strokes works optimally with standard mapping without requiring an inverse transposition matrix step.
- **Parallel Benchmarking:** For evaluating new models against the production model, run inferences synchronously inside parallel IO coroutine blocks and log timings/predictions to Logcat under specific debug tags (e.g. `DigitBenchmark`).
- **Coroutines Compatibility:** When wrapping ML Kit listener APIs (`addOnSuccessListener`) in `suspendCancellableCoroutine`, use the standard `cont.resume(value)` (requires importing `kotlin.coroutines.resume`) instead of the deprecated `resume(value, onCancellation)`.
- **ONNX Casts:** Model outputs (like `results[0].value`) return generic `Any!`. You must explicitly cast them (e.g. `as Array<FloatArray>`) and suppress the resulting unchecked cast warning (`@Suppress("UNCHECKED_CAST")`).

## E-ink UI Guidelines (Mudita UI Package)
We use the `com.mudita:MMD:1.0.2` UI package for all new and migrated UI elements to ensure a high-contrast, e-ink optimized aesthetic.
1. **Black-and-White Palette:** Unless explicitly specified otherwise, strictly use a black-and-white color palette for UI elements. Any grays or low-contrast elements must be migrated.
2. **Components:** Use Mudita components (e.g., `ButtonMMD`, `RadioButtonMMD`, `SwitchMMD`, `SliderMMD`, `HorizontalDividerMMD`) instead of standard Material3 elements when available.
3. **Migration Strategy:** Do not migrate all screens at once. Follow an incremental approach as requested per issue or task.
4. **Sizing:** Ensure UI element sizes and fonts are meaningful and absolute. Avoid scaling elements to become overly large on devices like the 13.3" Onyx screen.
5. **Component Scaling & Layout Truncation:** Mudita visual elements (like `SwitchMMD` and `RadioButtonMMD`) can be visually scaled using `Modifier.scale(0.75f)`. However, `scale()` only affects drawing bounds, leaving massive layout whitespace (hidden padding) around the component. To collapse this padding when scaling components down, apply a hard constraint to the parent row using `.height((36 * scale).dp)` (or `40.dp`), which visually overlaps the invisible touch padding and brings stacked items much closer together.
6. **Radio Button Groups:** `RadioButtonMMD` contains large internal padding. When building clustered lists of options, wrap the entire group in a `Column` with `.selectableGroup()` and set `Arrangement.spacedBy(0.dp)`. Move the click handling to the `Row` using `.selectable(role = Role.RadioButton)` and pass `onClick = null` into the `RadioButtonMMD` itself. This properly delegates the touch ripple and accessibility handling to the Row, bypassing the rigid internal constraints of the Mudita Radio Button.
7. **Main Menu Buttons:** Standard app navigation buttons (like "Settings") built with `ButtonMMD` should use a custom outline, e.g. `.border(BorderStroke((2 * scale).dp, Color.Black), RoundedCornerShape((16 * scale).dp))`, and maintain appropriate widths (e.g., `fillMaxWidth(0.4f)` for tertiary buttons).
8. **UI Alignment:** Keep labels and interactable elements (like Sliders) inline horizontally `Row(verticalAlignment = Alignment.CenterVertically)` to save vertical space on E-ink screens.
9. **Global State vs Sub-menus:** For E-ink applications, avoid deeply nested navigation sub-menus to reduce screen transitions and ghosting. For example, game configurations like "Difficulty" or "Zen Mode" toggles should be managed globally in a central `SettingsScreen` rather than through intermediary setup screens before starting a game.
10. **High-Density List Layouts:** For list-based overview screens (like Achievements), maximize screen utilization by replacing single-column lists with 2-column layouts (e.g., using `items.chunked(2)` with a `Row`). Eliminate redundant navigation buttons if covered by the system bar, enforce strict black-and-white colors over Material Theme alpha/tints, and reduce internal padding and spacers to condense card height.
11. **Text and Emojis:** Avoid using emojis in UI text elements (like screen titles or button labels) as they may render poorly or inconsistently in monochrome on e-ink displays. Stick to clean, text-only strings.
12. **Transparent Buttons vs Solid Colors:** Instead of solid black or gray filled buttons (`containerColor`), prefer `Color.Transparent` with a `BorderStroke` and solid black text. Only use gray colors for disabled or inactive states if absolutely necessary for logic feedback.
13. **Dialog Styling:** Dialogs (like `Pause`, `Game Over`, etc.) should avoid dark/solid backgrounds. Use a clean white background (`containerColor = Color.White`) with black text and simple borders or `RoundedCornerShape` without thick border outlines.
14. **Custom Fonts:** When incorporating custom fonts (like `dseg7` for digital timers), ensure they are conditionally applied so they don't override standard layout text (e.g., use the digital font only for the standard timer, not for the Zen Mode move count).
15. **Initial UI Load & Ghosting:** To reduce e-ink ghosting on heavy screens (like `GameScreen`), avoid showing "Resume Game" or setup dialogs immediately upon entering the screen. Instead, handle these pre-game states or dialogs on previous lightweight screens (like `MainScreen`) before navigating to the main game component.
16. **Action-First Interaction & State Toggle:** In game interaction workflows (like placing digits or erasing cells), prioritize an "action-first" model over "cell-first". The user toggles an action button to an "active" state, then taps the target cell to apply the action (one-shot).
17. **Active Button Styling:** Active states for toggleable action buttons should NOT invert the container and text colors (avoid black background with white text). Instead, keep the `containerColor` transparent and change the `contentColor` and `BorderStroke` to solid Black for the active state, and use Gray for the inactive state. This prevents aggressive e-ink flashing during fast interactions.
18. **Custom Handwriting Fonts:** Handwriting-style fonts (like `Caveat`) have unusually tall ascenders and descenders. When rendering them in tightly constrained layouts (like the 3x3 notes grid inside a Sudoku cell), do not set a `lineHeight` smaller than the `fontSize`, and apply `modifier = Modifier.wrapContentSize(unbounded = true)` to the `Text` to prevent the layout's bounding box from aggressively clipping the tops and bottoms of the digits.
19. **On-Demand Contextual Dialogs (Badges):** When a UI button toggle introduces a complex new mode (like 'Auto Notes'), avoid automatically popping up an informational `AlertDialog` every time the mode is selected. Instead, attach a subtle `Badge` (e.g., an 'i' info icon) to the button when that mode is active. Apply `Modifier.clickable` directly to the `Badge` so the user can open the informational dialog on-demand. Style the badge cleanly for e-ink: white background (`containerColor`), black border, black text, and use `Modifier.offset` to prevent overlapping adjacent layout elements.
20. **Dialog Component Styling:** Ensure all dialogs adhere strictly to the black-and-white palette. Remove any emojis from dialog titles or messages, as they render inconsistently on e-ink. If an icon is needed, use a `Vector` icon tinted solid black. Action buttons inside dialogs should span the full width (`Modifier.fillMaxWidth()`) with a `Color.Transparent` background, solid black text, and a simple black border. Avoid thick outer borders on the dialog container itself.
21. **Hyperlinks in UI & Dialogs:** When presenting links (e.g., model licenses or terms and conditions in setup dialogs), use `ClickableText` with an `AnnotatedString` where URLs are tagged (`pushStringAnnotation`), styled (e.g., `Color.Blue`, `TextDecoration.Underline`), and opened via `LocalUriHandler.current.openUri()`. Ensure standard dialog styling constraints are maintained (white backgrounds, black text).
22. **Download Progress & States:** For long-running asynchronous tasks like downloading ML models, use state-driven indicators such as `CircularProgressIndicator` or `LinearProgressIndicator`. Enforce E-ink friendly colors (`color = Color.Black`, `trackColor = Color.LightGray`) instead of default Material primary colors.
23. **Responsive Orientation Layouts:** When building distinct layouts for Portrait and Landscape modes (e.g., in `GameScreen`), ensure UI elements are grouped to optimize the available space for each orientation. For instance, in landscape mode, vertical space is constrained, so stack action buttons side-by-side using `Row` with `weight(1f)` components (e.g., grouping `Pencil` and `Notes` in one row) rather than creating long vertical columns that require scrolling.

## Project Fork & Build Constraints
- **Package Name:** The project was forked and the root package was migrated to `io.github.serg987.sudokueinkhtr`. The application ID and namespace reflect this. The app name is now `Sudoku E-Ink HTR`.
- **16KB Page Alignment:** The Onyx SDK includes native libraries (e.g., `libneo_pen.so`) that are compiled for 4KB page alignment. To ensure compatibility with Android 15+ 16KB page devices without triggering AGP build warnings, `useLegacyPackaging = true` is enforced in `app/build.gradle.kts` `jniLibs` packaging options. This extracts the `.so` to the filesystem during installation, delegating the alignment loading to the OS loader rather than mapping it directly from the uncompressed APK.
- **Jetifier Requirement:** Even though `android.enableJetifier=true` triggers a deprecation warning in modern AGP versions, it **must** remain enabled. The Onyx Pen SDK (`com.onyx.android.sdk:onyxsdk-pen`) and its dependencies (like `easypermissions`) rely on legacy `android.support` classes. Jetifier is required to automatically translate these into `androidx` classes during the build to prevent duplicate class conflicts and runtime crashes.
- **R8 API Modeling Bug:** The default R8 compiler shipped with AGP 9.0+ has a known bug where it throws a `NullPointerException` when performing API modeling on `LocationManagerCompat` (part of `androidx.core:core-ktx:1.15.0+`). To bypass this compiler crash and successfully build release variants, R8 API modeling is explicitly disabled by setting `systemProp.com.android.tools.r8.disableApiModeling=1` in `gradle.properties`.
- **R8 Missing Classes:** Some optional transitive dependencies (like `org.joda.convert` and `org.slf4j.impl`) are missing from the bundled libraries, causing R8 minification to fail. Explicit `-dontwarn` rules for these packages must be maintained in `app/proguard-rules.pro`.

## Known Issues
- **Dark Mode**: Dark mode is currently not working and requires many adjustments. The switch in the settings menu has been temporarily commented out.
- **Switching screen orientation**: If a current or saved game with handwritten notes is loaded in the wrong orientation, handwritten notes are not aligned. Leave for later.
