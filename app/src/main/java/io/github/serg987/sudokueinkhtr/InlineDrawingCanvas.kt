package io.github.serg987.sudokueinkhtr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Diagnostics for the non-Onyx stylus channel — mirrors NonogramEink's `NonoStylus`. */
private const val STYLUS_TAG = "SudoStylus"

@Composable
fun InlineDrawingCanvas(
    modifier: Modifier = Modifier,
    inkStrokes: Map<Pair<Int, Int>, List<List<DrawingPoint>>>,
    onInkStrokesChanged: (Map<Pair<Int, Int>, List<List<DrawingPoint>>>) -> Unit,
    notesMode: NotesMode,
    onDigitRecognized: (digit: Int, row: Int, col: Int) -> Unit,
    onClearCell: (row: Int, col: Int) -> Unit = { _, _ -> },
    isCellFilledWithPencil: (row: Int, col: Int) -> Boolean = { _, _ -> false },
    isCellFixed: (row: Int, col: Int) -> Boolean = { _, _ -> false },
    penActiveState: MutableState<Boolean>? = null,
    // When false the pen is disabled as an *input device*: no wet-ink render, no
    // recognition, no erase. The persisted strokes stay mounted and visible (that is why
    // this canvas is no longer wrapped in `if (isPencilMode)` at the call site) — toggling
    // the pencil button must never make already-written handwriting disappear.
    penInputEnabled: Boolean = true
) {
    val context = LocalContext.current
    val scaleFactor = AdaptiveSizes.getScaleFactor()

    var paths by remember { mutableStateOf(mutableListOf<List<DrawingPoint>>()) }
    var touchHelper by remember { mutableStateOf<TouchHelper?>(null) }
    // Host view for both device pipelines: the Onyx SurfaceView, or the non-Onyx
    // FrameLayout wrapping the persisted-ink view + wet-ink overlay.
    var rootView by remember { mutableStateOf<View?>(null) }
    // Non-Onyx only. Null (and unused) on Onyx.
    var persistedInkView by remember { mutableStateOf<PersistedInkView?>(null) }
    var inkOverlayView by remember { mutableStateOf<InProgressStrokesView?>(null) }
    var motionPredictor by remember { mutableStateOf<MotionEventPredictor?>(null) }
    var layoutListenerAttached by remember { mutableStateOf(false) }

    var viewWidth by remember { mutableIntStateOf(0) }
    var viewHeight by remember { mutableIntStateOf(0) }

    var lastStrokeTime by remember { mutableLongStateOf(0L) }
    var isErasing by remember { mutableStateOf(false) }

    // ---- Non-Onyx software stylus capture state (see handleSoftwareStylusEvent below).
    // Onyx never touches these — capture happens through TouchHelper's RawInputCallback
    // instead.
    var softwareStroke by remember { mutableStateOf<MutableList<DrawingPoint>?>(null) }
    var softwareStrokeErasing by remember { mutableStateOf(false) }
    var softwareStrokePointerId by remember { mutableIntStateOf(-1) }
    var softwareInkStrokeId by remember { mutableStateOf<InProgressStrokeId?>(null) }

    // CRITICAL: We MUST use rememberUpdatedState for all lambda parameters passed to the AndroidView callbacks.
    // The AndroidView's `update` lambda only initializes the Onyx `RawInputCallback` and `onTouchListener` ONCE.
    // If we don't wrap these, those listeners will permanently capture the lambdas from the very first composition.
    // This causes bugs where starting a "New Game" creates a new `boardState`, but erasing a cell modifies the
    // discarded old `boardState` instead of the active one because it's using the stale lambda.
    val currentOnClearCell by rememberUpdatedState(onClearCell)
    val currentOnDigitRecognized by rememberUpdatedState(onDigitRecognized)
    val currentIsCellFilledWithPencil by rememberUpdatedState(isCellFilledWithPencil)
    val currentIsCellFixed by rememberUpdatedState(isCellFixed)
    val currentInkStrokes by rememberUpdatedState(inkStrokes)
    // Same rememberUpdatedState discipline as the lambdas above: the RawInputCallback and
    // onTouchListener are built once in the AndroidView `update` block, so they must read
    // the live pen-input flag through this delegate, not a value captured at first compose.
    val currentPenInputEnabled by rememberUpdatedState(penInputEnabled)

    var eraseRow by remember { mutableIntStateOf(-1) }
    var eraseCol by remember { mutableIntStateOf(-1) }

    // CRITICAL: Debouncer map for onClearCell.
    // Onyx devices fire onEndRawErasing multiple times in rapid succession for a single tap. If Compose UI is
    // updated too early (while the hardware is still flashing), the E-ink screen drops the Android invalidate.
    // We debounce the state update per cell to ensure it executes exactly ONCE, strictly 600ms after the LAST hardware event.
    val clearRunnables = remember { mutableMapOf<Pair<Int, Int>, Runnable>() }

    val htrModel = remember { SettingsManager.loadHtrModel(context) }

    val recognizer = remember(htrModel) {
        if (htrModel == HtrModel.TFLITE) DigitRecognizer(context) else null
    }
    val onnxRecognizer = remember(htrModel) {
        if (htrModel == HtrModel.ONNX) OnnxDigitRecognizer(context) else null
    }
    val mlKitRecognizer = remember(htrModel) {
        if (htrModel == HtrModel.MLKIT) MlKitDigitRecognizer(context) else null
    }

    // Owns the raw-ink flush lifecycle (see InkFlushController doc). Reads the live
    // inkStrokes through the rememberUpdatedState delegate above so a flush that fires
    // after inkStrokes has changed again still redraws the current strokes, not a stale
    // snapshot from when the flush was scheduled.
    val controller = remember {
        InkFlushController(
            strokesProvider = { currentInkStrokes },
            strokeWidthProvider = { AppConfig.handwritingStrokeThickness * scaleFactor }
        )
    }
    // Mirror pen-active state out to the caller (GameScreen holds the timer's display
    // steady while the pen is active, so a periodic recomposition can't race a pen-down
    // and eat the wet ink of the digit currently being written).
    LaunchedEffect(controller.penActiveState.value) {
        penActiveState?.value = controller.penActiveState.value
    }

    // Turn native wet-ink rendering on/off with the pencil button. This is the SDK render
    // flag (setRawDrawingRenderEnabled), NOT the banned setRawDrawingEnabled mode toggle
    // (AGENTS.md "Clearing Ink"): it performs no scribble-mode exit / pen-state transition,
    // and it is driven only by the pencil button — never mid-stroke — so it cannot race a
    // pen-down. Disabling render (plus the callback gates below) is what makes the pen inert
    // as an input device while the persisted-stroke overlay keeps rendering through the
    // controller's own canvas draws. No-op on non-Onyx: touchHelper is never created there
    // (currentPenInputEnabled is checked directly in handleSoftwareStylusEvent instead).
    LaunchedEffect(penInputEnabled, touchHelper) {
        touchHelper?.setRawDrawingRenderEnabled(penInputEnabled)
    }

    DisposableEffect(htrModel) {
        onDispose {
            controller.reset()
            touchHelper?.closeRawDrawing()
            if (!DeviceCaps.isOnyx) {
                // A mid-stroke teardown means no ACTION_UP/CANCEL will ever arrive for an
                // in-progress overlay stroke — cancel it explicitly (NonogramEink
                // onHostPause parity).
                softwareInkStrokeId?.let { id -> inkOverlayView?.cancelStroke(id) }
                softwareStroke = null
                softwareInkStrokeId = null
                softwareStrokePointerId = -1
            }
            recognizer?.close()
            onnxRecognizer?.close()
            mlKitRecognizer?.close()
        }
    }

    // The actual redraw now always goes through the flush controller (repaint bracket +
    // enablePost, see InkFlushController) instead of drawing directly here — this single
    // LaunchedEffect covers every inkStrokes change (initial load, undo/redo, recognition
    // commits, erases) with one consistent code path.
    LaunchedEffect(inkStrokes, viewWidth, viewHeight) {
        if (viewWidth > 0 && viewHeight > 0) {
            controller.scheduleFlush("inkStrokesChanged", InkFlushController.COMMIT_FLUSH_DELAY_MS)
        }
    }

    LaunchedEffect(lastStrokeTime) {
        if (lastStrokeTime > 0) {
            Log.d("InlineDrawingCanvas", "Timeout started for lastStrokeTime=$lastStrokeTime")
            delay(500) // 500ms timeout
            Log.d("InlineDrawingCanvas", "Timeout finished. paths size=${paths.size}, viewWidth=$viewWidth, viewHeight=$viewHeight")
            if (paths.isNotEmpty() && viewWidth > 0 && viewHeight > 0) {
                // Find first point to determine row/col
                val firstPoint = paths.firstOrNull()?.firstOrNull()
                Log.d("InlineDrawingCanvas", "First point: $firstPoint")
                if (firstPoint != null) {
                    val col = (firstPoint.x / (viewWidth / 9f)).toInt().coerceIn(0, 8)
                    val row = (firstPoint.y / (viewHeight / 9f)).toInt().coerceIn(0, 8)
                    Log.d("InlineDrawingCanvas", "Mapped to row=$row, col=$col")

                    if (isCellFixed(row, col)) {
                        paths = mutableListOf()
                        lastStrokeTime = 0L
                        controller.scheduleFlush("fixedCellDiscard", InkFlushController.COMMIT_FLUSH_DELAY_MS)
                        return@LaunchedEffect
                    }

                    var minX = Float.MAX_VALUE
                    var minY = Float.MAX_VALUE
                    var maxX = -Float.MAX_VALUE
                    var maxY = -Float.MAX_VALUE
                    paths.forEach { list ->
                        list.forEach { p ->
                            if (p.x < minX) minX = p.x
                            if (p.x > maxX) maxX = p.x
                            if (p.y < minY) minY = p.y
                            if (p.y > maxY) maxY = p.y
                        }
                    }
                    val isMicroStroke = (maxX - minX) < (20f * scaleFactor) && (maxY - minY) < (20f * scaleFactor)
                    if (isMicroStroke) {
                        Log.d("InlineDrawingCanvas", "Micro-stroke ignored")
                        paths = mutableListOf()
                        lastStrokeTime = 0L
                        controller.scheduleFlush("microStrokeDiscard", InkFlushController.COMMIT_FLUSH_DELAY_MS)
                        return@LaunchedEffect
                    }

                    val cellWidth = viewWidth / 9f
                    val cellHeight = viewHeight / 9f
                    val toleranceX = cellWidth * 0.1f
                    val toleranceY = cellHeight * 0.1f

                    val minCol = ((minX + toleranceX) / cellWidth).toInt().coerceIn(0, 8)
                    val maxCol = ((maxX - toleranceX) / cellWidth).toInt().coerceIn(0, 8)
                    val minRow = ((minY + toleranceY) / cellHeight).toInt().coerceIn(0, 8)
                    val maxRow = ((maxY - toleranceY) / cellHeight).toInt().coerceIn(0, 8)

                    if (minCol != maxCol || minRow != maxRow) {
                        Log.d("InlineDrawingCanvas", "Stroke spans multiple cells. Ignored.")
                        paths = mutableListOf()
                        lastStrokeTime = 0L
                        controller.scheduleFlush("multiCellDiscard", InkFlushController.COMMIT_FLUSH_DELAY_MS)
                        return@LaunchedEffect
                    }

                    val cellKey = Pair(row, col)

                    if (currentIsCellFixed(row, col)) {
                        Log.d("InlineDrawingCanvas", "Cell is fixed. Ignoring handwriting.")
                        paths = mutableListOf()
                        lastStrokeTime = 0L

                        if (inkStrokes.containsKey(cellKey)) {
                            val newStrokes = inkStrokes.toMutableMap()
                            newStrokes.remove(cellKey)
                            onInkStrokesChanged(newStrokes)
                        }

                        controller.scheduleFlush("fixedCellCleanup", InkFlushController.COMMIT_FLUSH_DELAY_MS)
                        return@LaunchedEffect
                    }

                    if (currentIsCellFilledWithPencil(row, col) && isEraseGesture(paths, viewWidth / 9f, viewHeight / 9f)) {
                        Log.d("InlineDrawingCanvas", "Erase gesture detected for row=$row, col=$col")
                        val cellKey = Pair(row, col)
                        val newStrokes = currentInkStrokes.toMutableMap()
                        newStrokes.remove(cellKey)
                        onInkStrokesChanged(newStrokes)

                        clearRunnables[cellKey]?.let { rootView?.removeCallbacks(it) }
                        val r = Runnable {
                            Log.d("InlineDrawingCanvas", "currentOnClearCell executing safely after debounce (gesture)")
                            currentOnClearCell(row, col)
                        }
                        clearRunnables[cellKey] = r
                        rootView?.postDelayed(r, 600)

                        controller.scheduleFlush("eraseGestureCommit", InkFlushController.COMMIT_FLUSH_DELAY_MS)

                        paths = mutableListOf()
                        lastStrokeTime = 0L
                        return@LaunchedEffect
                    }

                    val existingStrokes = currentInkStrokes[cellKey]?.toMutableList() ?: mutableListOf()
                    existingStrokes.addAll(paths)

                    if (notesMode == NotesMode.MANUAL) {
                        Log.d("InlineDrawingCanvas", "Manual notes mode. Bypassing recognition.")
                        onInkStrokesChanged(currentInkStrokes + (cellKey to existingStrokes))
                    } else {
                        val strokeWidth = 20f * scaleFactor
                        val digit = withContext(Dispatchers.IO) {
                            val bitmap = inlinePathsToBitmap(paths, viewWidth, viewHeight, strokeWidth)

                            when (htrModel) {
                                HtrModel.TFLITE -> {
                                    recognizer?.recognizeDigit(bitmap) ?: -1
                                }
                                HtrModel.ONNX -> {
                                    val onnxResult = onnxRecognizer?.recognizeDigit(bitmap)
                                    val digitResult = onnxResult?.digit ?: -1
                                    // Treat blank class (10) as unrecognized or zero
                                    if (digitResult == 10) -1 else digitResult
                                }
                                HtrModel.MLKIT -> {
                                    val mlKitResult = mlKitRecognizer?.recognizeDigitAsync(paths, viewWidth / 9f, viewHeight / 9f)
                                    mlKitResult?.digit ?: -1
                                }
                            }
                        }
                        Log.d("InlineDrawingCanvas", "Recognized digit (used for app): $digit using model $htrModel")

                        onDigitRecognized(digit, row, col)
                        onInkStrokesChanged(currentInkStrokes + (cellKey to existingStrokes))
                    }
                }

                // Clear state
                paths = mutableListOf()
                lastStrokeTime = 0L

                controller.scheduleFlush("recognitionCommit", InkFlushController.COMMIT_FLUSH_DELAY_MS)
            }
        }
    }

    /**
     * Non-Onyx stylus path: captures the stroke from `MotionEvent`s (with historical
     * points, so the gesture recognizer sees the same point density the Onyx raw channel
     * delivers) and drives the wet-ink overlay directly. Ported from NonogramEink's
     * `handleSoftwareStylusEvent` (`NonogramGridView.kt`), adapted to Sudoku's whole-cell
     * erase semantics (mirrors the Onyx `onBeginRawErasing`/`onEndRawErasing` pair below,
     * rather than Nonogram's per-tile toggle).
     *
     * State transitions map onto the Onyx `RawInputCallback` this replaces: DOWN =
     * `onBeginRaw*`, points accumulated per MOVE = `onRaw*TouchPointListReceived`, UP =
     * `onEndRaw*`, CANCEL = a stroke that never produces a list callback (flush still
     * re-armed so `isPenActive` always clears).
     *
     * ☠ Erase decision quirk (device-verified on this exact Teclast pen via NonogramEink):
     * the himax driver can report a barrel button held *before* touch-down as
     * `buttonState=0`, with the flag only appearing in the first `ACTION_MOVE` a few ms
     * later. The erase check therefore re-runs on every MOVE as an **upgrade-only** flip
     * (never downgraded once true) — any wet ink already drawn before the upgrade is
     * cancelled, exactly like Onyx's raw erase channel never draws ink at all.
     */
    fun handleSoftwareStylusEvent(view: View, event: MotionEvent): Boolean {
        if (!currentPenInputEnabled) return false

        val toolType = event.getToolType(0)
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS && toolType != MotionEvent.TOOL_TYPE_ERASER) {
            // Finger (or anything else): fall through so SudokuBoard's combinedClickable
            // handles cell selection and the action-first digit/erase/notes buttons — the
            // whole reason no TouchHelper is created on non-Onyx (see DeviceCaps doc; its
            // undocumented fallback eats these MotionEvents).
            return false
        }

        motionPredictor?.record(event)

        val isEraserNow = toolType == MotionEvent.TOOL_TYPE_ERASER ||
            (event.buttonState and
                (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0

        if (event.actionMasked != MotionEvent.ACTION_MOVE) {
            Log.d(
                STYLUS_TAG,
                "action=${event.actionMasked} toolType=$toolType isEraser=$isEraserNow" +
                    " buttonState=${event.buttonState} pressure=${event.pressure}"
            )
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                controller.isPenDown = true
                controller.isPenActive = true
                controller.cancelPendingFlush("softStylusDown")

                softwareStrokePointerId = event.getPointerId(0)
                softwareStroke = mutableListOf(DrawingPoint(event.x, event.y, event.eventTime))
                softwareStrokeErasing = isEraserNow
                eraseRow = (event.y / (viewHeight / 9f)).toInt().coerceIn(0, 8)
                eraseCol = (event.x / (viewWidth / 9f)).toInt().coerceIn(0, 8)

                if (softwareStrokeErasing) {
                    // Discard anything mid-recognition the instant we enter erase mode —
                    // mirrors onBeginRawErasing.
                    paths = mutableListOf()
                    lastStrokeTime = 0L
                } else {
                    view.requestUnbufferedDispatch(event)
                    val brush = Brush.createWithColorIntArgb(
                        family = StockBrushes.pressurePen(),
                        colorIntArgb = Color.BLACK,
                        size = AppConfig.handwritingStrokeThickness * scaleFactor,
                        epsilon = 0.1f
                    )
                    softwareInkStrokeId = inkOverlayView?.startStroke(event, softwareStrokePointerId, brush)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!softwareStrokeErasing && isEraserNow) {
                    // Late-arriving button (see doc above): drop whatever wet ink was
                    // drawn so far — Onyx's raw erase channel never draws ink either.
                    softwareStrokeErasing = true
                    paths = mutableListOf()
                    lastStrokeTime = 0L
                    softwareInkStrokeId?.let { id -> inkOverlayView?.cancelStroke(id, event) }
                    softwareInkStrokeId = null
                }
                if (softwareStrokeErasing) return true

                val stroke = softwareStroke ?: return true
                val idx = event.findPointerIndex(softwareStrokePointerId)
                if (idx < 0) return true
                for (h in 0 until event.historySize) {
                    stroke.add(
                        DrawingPoint(
                            event.getHistoricalX(idx, h),
                            event.getHistoricalY(idx, h),
                            event.getHistoricalEventTime(h)
                        )
                    )
                }
                stroke.add(DrawingPoint(event.getX(idx), event.getY(idx), event.eventTime))
                softwareInkStrokeId?.let { id ->
                    val predicted = motionPredictor?.predict()
                    try {
                        inkOverlayView?.addToStroke(event, softwareStrokePointerId, id, predicted)
                    } finally {
                        predicted?.recycle()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                controller.isPenDown = false
                if (softwareStrokeErasing) {
                    val row = eraseRow
                    val col = eraseCol
                    paths = mutableListOf()
                    lastStrokeTime = 0L
                    if (row != -1 && col != -1) {
                        val cellKey = Pair(row, col)
                        val newStrokes = currentInkStrokes.toMutableMap()
                        newStrokes.remove(cellKey)
                        onInkStrokesChanged(newStrokes)

                        clearRunnables[cellKey]?.let { view.removeCallbacks(it) }
                        val r = Runnable {
                            Log.d("InlineDrawingCanvas", "currentOnClearCell executing safely after debounce (software eraser)")
                            currentOnClearCell(row, col)
                        }
                        clearRunnables[cellKey] = r
                        // 600ms mirrors the Onyx debounce (AGENTS.md "E-ink Hardware
                        // Erase Conflict") for behavioral parity, even though software
                        // erase has no hardware refresh race to wait out on LCD.
                        view.postDelayed(r, 600)
                    }
                    controller.scheduleFlush("softEraserUp", InkFlushController.COMMIT_FLUSH_DELAY_MS)
                } else {
                    softwareInkStrokeId?.let { id -> inkOverlayView?.finishStroke(event, softwareStrokePointerId, id) }
                    val stroke = softwareStroke
                    if (stroke != null) {
                        val currentPaths = paths.toMutableList()
                        currentPaths.add(stroke)
                        paths = currentPaths
                        lastStrokeTime = System.currentTimeMillis()
                    }
                    controller.scheduleFlush("softStylusUp", InkFlushController.PEN_UP_SAFETY_DELAY_MS)
                }
                softwareStroke = null
                softwareInkStrokeId = null
                softwareStrokePointerId = -1
                softwareStrokeErasing = false
                eraseRow = -1
                eraseCol = -1
            }
            MotionEvent.ACTION_CANCEL -> {
                controller.isPenDown = false
                softwareInkStrokeId?.let { id -> inkOverlayView?.cancelStroke(id, event) }
                softwareStroke = null
                softwareInkStrokeId = null
                softwareStrokePointerId = -1
                softwareStrokeErasing = false
                eraseRow = -1
                eraseCol = -1
                controller.scheduleFlush("softStylusCancel", InkFlushController.PEN_UP_SAFETY_DELAY_MS)
            }
        }
        return true
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            Log.d(STYLUS_TAG, "InlineDrawingCanvas init: DeviceCaps.isOnyx=${DeviceCaps.isOnyx}")
            if (DeviceCaps.isOnyx) {
                SurfaceView(ctx).apply {
                    setZOrderOnTop(true)
                    holder.setFormat(PixelFormat.TRANSPARENT)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            // Fires on first layout AND whenever the underlying Surface is torn
                            // down and rebuilt behind our back -- e.g. backgrounding the app,
                            // which destroys/recreates the Surface even though this SurfaceView
                            // instance and inkStrokes are untouched. A fresh surface starts
                            // blank, so redraw the persisted strokes immediately instead of
                            // leaving the ink layer empty until the next stroke's flush.
                            // MUST be the plain EPD-free redraw, NOT scheduleFlush: the repaint
                            // bracket inside this transition wedges the scribble render channel
                            // (no live wet ink + flashing cells) -- see redrawStrokes' doc.
                            controller.redrawStrokes("surfaceCreated")
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) {}
                    })

                    setOnTouchListener { _, event ->
                        // Pen disabled as an input device: swallow nothing, handle nothing. Return
                        // false so the touch falls through to the Sudoku grid below (finger cell
                        // selection keeps working) and no eraser/re-arm logic runs.
                        if (!currentPenInputEnabled) return@setOnTouchListener false

                        val toolType = event.getToolType(0)
                        val isStylusOrEraser = toolType == android.view.MotionEvent.TOOL_TYPE_STYLUS ||
                            toolType == android.view.MotionEvent.TOOL_TYPE_ERASER

                        // Second, independent re-arm channel for the trailing flush (mirrors
                        // NonogramEink's stranded-stroke fix): the stylus is double-dispatched
                        // (raw SDK channel + MotionEvent both fire), so a stray ACTION_DOWN the
                        // raw channel discards as noise could otherwise cancel a pending flush
                        // with no onEndRaw* ever following to re-arm it. This channel re-arms
                        // independently of RawInputCallback, guarded so it never fights a raw
                        // stroke actually in progress.
                        if (isStylusOrEraser) {
                            when (event.actionMasked) {
                                android.view.MotionEvent.ACTION_DOWN ->
                                    controller.cancelPendingFlush("stylusTouchDown")
                                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                                    if (!controller.isPenDown) {
                                        controller.scheduleFlush("stylusTouchUp", InkFlushController.PEN_UP_SAFETY_DELAY_MS)
                                    }
                            }
                        }

                        val isEraser = toolType == android.view.MotionEvent.TOOL_TYPE_ERASER ||
                            (toolType == android.view.MotionEvent.TOOL_TYPE_STYLUS &&
                            (event.buttonState and android.view.MotionEvent.BUTTON_STYLUS_PRIMARY != 0 ||
                             event.buttonState and android.view.MotionEvent.BUTTON_SECONDARY != 0))

                        Log.d("InlineDrawingCanvas", "onTouch: actionMasked=${event.actionMasked}, isEraser=$isEraser, buttonState=${event.buttonState}, toolType=${event.getToolType(0)}")

                        if (isEraser || isErasing) {
                            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN && isEraser) {
                                Log.d("InlineDrawingCanvas", "onTouch ACTION_DOWN (eraser). Setting isErasing=true, clearing cell")
                                isErasing = true
                                paths = mutableListOf()
                                lastStrokeTime = 0L

                                val col = (event.x / (viewWidth / 9f)).toInt().coerceIn(0, 8)
                                val row = (event.y / (viewHeight / 9f)).toInt().coerceIn(0, 8)
                                Log.d("InlineDrawingCanvas", "onTouch ACTION_DOWN (eraser). Setting isErasing=true, clearing cell row=$row, col=$col")

                                eraseRow = row
                                eraseCol = col

                                val cellKey = Pair(row, col)
                                val newStrokes = currentInkStrokes.toMutableMap()
                                newStrokes.remove(cellKey)
                                onInkStrokesChanged(newStrokes)

                                // We delay the onClearCell to ACTION_UP to avoid e-ink refresh conflicts

                                controller.scheduleFlush("eraserTouchDown", InkFlushController.COMMIT_FLUSH_DELAY_MS)

                                return@setOnTouchListener true
                            } else if (event.actionMasked == android.view.MotionEvent.ACTION_UP || event.actionMasked == android.view.MotionEvent.ACTION_CANCEL) {
                                Log.d("InlineDrawingCanvas", "onTouch ACTION_UP/CANCEL (eraser). Setting isErasing=false after 500ms delay")
                                paths = mutableListOf()
                                lastStrokeTime = 0L

                                val row = if (eraseRow != -1) eraseRow else (event.y / (viewHeight / 9f)).toInt().coerceIn(0, 8)
                                val col = if (eraseCol != -1) eraseCol else (event.x / (viewWidth / 9f)).toInt().coerceIn(0, 8)

                                val cellKey = Pair(row, col)
                                clearRunnables[cellKey]?.let { removeCallbacks(it) }
                                val r = Runnable {
                                    Log.d("InlineDrawingCanvas", "currentOnClearCell executing safely after debounce (ACTION_UP)")
                                    // Invoking the current lambda using rememberUpdatedState to avoid stale boardState
                                    currentOnClearCell(row, col)
                                }
                                clearRunnables[cellKey] = r
                                // 600ms delay ensures native e-ink hardware erase completely finishes before Android renders
                                postDelayed(r, 600)
                                postDelayed({ isErasing = false }, 600)

                                eraseRow = -1
                                eraseCol = -1
                                return@setOnTouchListener true
                            }
                        }
                        false
                    }

                    rootView = this
                    controller.hostView = this
                    controller.onyxSurface = this
                }
            } else {
                FrameLayout(ctx).apply {
                    val persisted = PersistedInkView(ctx).apply {
                        strokesProvider = { currentInkStrokes }
                        strokeWidthProvider = { AppConfig.handwritingStrokeThickness * scaleFactor }
                    }
                    addView(
                        persisted,
                        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    )

                    val overlay = InProgressStrokesView(ctx)
                    overlay.eagerInit()
                    overlay.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
                        override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                            // UI thread. Held rendered until the trailing flush clears them
                            // (InkFlushController.clearSoftwareInk).
                            controller.finishedInkIds.addAll(strokes.keys)
                        }
                    })
                    addView(
                        overlay,
                        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    )

                    setOnTouchListener { _, event -> handleSoftwareStylusEvent(this, event) }

                    motionPredictor = MotionEventPredictor.newInstance(this)
                    persistedInkView = persisted
                    inkOverlayView = overlay
                    rootView = this
                    controller.hostView = this
                    controller.persistedView = persisted
                    controller.inkOverlay = overlay
                }
            }
        },
        update = { view ->
            if (!layoutListenerAttached) {
                layoutListenerAttached = true
                view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        v: View, left: Int, top: Int, right: Int, bottom: Int,
                        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                    ) {
                        view.removeOnLayoutChangeListener(this)
                        viewWidth = right - left
                        viewHeight = bottom - top

                        // No TouchHelper is ever created on non-Onyx (see DeviceCaps doc):
                        // its undocumented fallback would consume the stylus AND finger
                        // MotionEvents the software path (and SudokuBoard's cell taps)
                        // depend on. handleSoftwareStylusEvent, wired in `factory` above,
                        // is the entire non-Onyx pen pipeline.
                        if (!DeviceCaps.isOnyx) return

                        val limit = Rect()
                        view.getLocalVisibleRect(limit)

                        val callback = object : RawInputCallback() {
                            // THREADING: these methods run on the Onyx SDK's private
                            // background executor, never the main thread. Only flag
                            // flips and controller scheduling happen directly here;
                            // everything that touches Compose state is posted to main
                            // via view.post { } (see InkFlushController's doc for why).
                            override fun onBeginRawDrawing(b: Boolean, touchPoint: TouchPoint) {
                                controller.isPenDown = true
                                controller.isPenActive = true
                                controller.cancelPendingFlush("beginDraw")
                            }
                            override fun onEndRawDrawing(b: Boolean, touchPoint: TouchPoint) {
                                controller.isPenDown = false
                                controller.scheduleFlush("endDraw", InkFlushController.PEN_UP_SAFETY_DELAY_MS)
                                view.post {
                                    // Skip arming recognition when the pen is disabled — the
                                    // flush scheduled above still clears any transient ink.
                                    if (currentPenInputEnabled && !isErasing) lastStrokeTime = System.currentTimeMillis()
                                }
                            }
                            override fun onRawDrawingTouchPointMoveReceived(touchPoint: TouchPoint) {}
                            override fun onRawDrawingTouchPointListReceived(touchPointList: TouchPointList) {
                                // Copy off the SDK's recycled list on this thread; the
                                // Compose state append itself is posted to main.
                                val points = touchPointList.points.map { DrawingPoint(it.x, it.y, it.timestamp) }
                                view.post {
                                    if (currentPenInputEnabled && !isErasing) {
                                        val currentPaths = paths.toMutableList()
                                        currentPaths.add(points)
                                        paths = currentPaths
                                    }
                                }
                            }
                            override fun onBeginRawErasing(b: Boolean, touchPoint: TouchPoint) {
                                controller.isPenDown = true
                                controller.isPenActive = true
                                controller.cancelPendingFlush("beginErase")
                                view.post {
                                    if (!currentPenInputEnabled) return@post
                                    isErasing = true
                                    paths = mutableListOf()
                                    lastStrokeTime = 0L
                                }
                            }
                            override fun onEndRawErasing(b: Boolean, touchPoint: TouchPoint) {
                                controller.isPenDown = false
                                controller.scheduleFlush("endErase", InkFlushController.COMMIT_FLUSH_DELAY_MS)
                                view.post {
                                    if (!currentPenInputEnabled) return@post
                                    val col = (touchPoint.x / (viewWidth / 9f)).toInt().coerceIn(0, 8)
                                    val row = (touchPoint.y / (viewHeight / 9f)).toInt().coerceIn(0, 8)
                                    Log.d("InlineDrawingCanvas", "onEndRawErasing. row=$row, col=$col")

                                    val cellKey = Pair(row, col)
                                    val newStrokes = currentInkStrokes.toMutableMap()
                                    newStrokes.remove(cellKey)
                                    onInkStrokesChanged(newStrokes)

                                    clearRunnables[cellKey]?.let { rootView?.removeCallbacks(it) }
                                    val r = Runnable {
                                        Log.d("InlineDrawingCanvas", "currentOnClearCell executing safely after debounce (onEndRawErasing)")
                                        // Invoking the current lambda using rememberUpdatedState to avoid stale boardState
                                        currentOnClearCell(row, col)
                                    }
                                    clearRunnables[cellKey] = r
                                    // 600ms delay ensures native e-ink hardware erase completely finishes before Android renders
                                    rootView?.postDelayed(r, 600)

                                    paths = mutableListOf()
                                    lastStrokeTime = 0L
                                    rootView?.postDelayed({ isErasing = false }, 500)
                                }
                            }
                            override fun onRawErasingTouchPointMoveReceived(touchPoint: TouchPoint) {}
                            override fun onRawErasingTouchPointListReceived(touchPointList: TouchPointList) {}
                        }

                        val th = TouchHelper.create(view, callback)
                        val strokeWidth = 20f * scaleFactor
                        th.setStrokeWidth(strokeWidth)
                          .setLimitRect(limit, ArrayList<Rect>())
                          .openRawDrawing()
                        th.setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN)
                        th.setRawDrawingEnabled(true)
                        th.setRawDrawingRenderEnabled(true)

                        touchHelper = th
                    }
                })
            }
        }
    )
}

private fun inlinePathsToBitmap(pathsList: List<List<DrawingPoint>>, width: Int, height: Int, strokeWidth: Float): Bitmap {
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    pathsList.forEach { list ->
        list.forEach { p ->
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
    }

    // Fallback if no points
    if (minX > maxX || minY > maxY) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        return bitmap
    }

    val padding = strokeWidth * 2
    minX = (minX - padding).coerceAtLeast(0f)
    minY = (minY - padding).coerceAtLeast(0f)
    maxX = (maxX + padding).coerceAtMost(width.toFloat())
    maxY = (maxY + padding).coerceAtMost(height.toFloat())

    val cropWidth = (maxX - minX).toInt().coerceAtLeast(1)
    val cropHeight = (maxY - minY).toInt().coerceAtLeast(1)

    val bitmap = Bitmap.createBitmap(cropWidth, cropHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.BLACK)

    val paint = Paint().apply {
        color = Color.WHITE
        this.strokeWidth = strokeWidth
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    pathsList.forEach { list ->
        if (list.isNotEmpty()) {
            val path = Path()
            var prePoint = list[0]
            path.moveTo(prePoint.x - minX, prePoint.y - minY)
            for (i in 1 until list.size) {
                val point = list[i]
                path.quadTo(prePoint.x - minX, prePoint.y - minY, point.x - minX, point.y - minY)
                prePoint = point
            }
            canvas.drawPath(path, paint)
        }
    }

    return bitmap
}

private fun isEraseGesture(pathsList: List<List<DrawingPoint>>, cellWidth: Float, cellHeight: Float): Boolean {
    if (pathsList.isEmpty()) return false

    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE

    pathsList.forEach { list ->
        list.forEach { p ->
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
    }

    // 1. Strike-through (-)
    if (pathsList.size == 1) {
        val stroke = pathsList[0]
        if (stroke.size > 2) {
            val startP = stroke.first()
            val endP = stroke.last()
            val dx = kotlin.math.abs(endP.x - startP.x)
            val dy = kotlin.math.abs(endP.y - startP.y)
            if (dx > cellWidth * 0.4f) {
                // Check if it's within 60 degrees of horizontal (tan 60 = 1.732)
                if (dy <= dx * 1.75f) {
                    return true
                }
            }
        }
    }

    // 2. Scribble (zig-zag)
    // Works across multiple strokes
    var reversals = 0
    var lastDir = 0
    pathsList.forEach { stroke ->
        for (i in 1 until stroke.size) {
            val dx = stroke[i].x - stroke[i-1].x
            if (kotlin.math.abs(dx) > 5f) {
                val currentDir = if (dx > 0) 1 else -1
                if (lastDir != 0 && currentDir != lastDir) {
                    reversals++
                }
                lastDir = currentDir
            }
        }
    }
    if (reversals >= 4) {
        val dxTotal = maxX - minX
        if (dxTotal > cellWidth * 0.3f) {
            return true
        }
    }

    // 3. Cross out (X)
    if (pathsList.size == 2) {
        val stroke1 = pathsList[0]
        val stroke2 = pathsList[1]

        fun isDiagonal(s: List<DrawingPoint>): Boolean {
            if (s.size < 2) return false
            val dx = kotlin.math.abs(s.last().x - s.first().x)
            val dy = kotlin.math.abs(s.last().y - s.first().y)
            return dx > cellWidth * 0.3f && dy > cellHeight * 0.3f
        }

        if (isDiagonal(stroke1) && isDiagonal(stroke2)) {
            val s1MinX = stroke1.minOf { it.x }
            val s1MaxX = stroke1.maxOf { it.x }
            val s1MinY = stroke1.minOf { it.y }
            val s1MaxY = stroke1.maxOf { it.y }

            val s2MinX = stroke2.minOf { it.x }
            val s2MaxX = stroke2.maxOf { it.x }
            val s2MinY = stroke2.minOf { it.y }
            val s2MaxY = stroke2.maxOf { it.y }

            if (s1MinX < s2MaxX && s1MaxX > s2MinX && s1MinY < s2MaxY && s1MaxY > s2MinY) {
                return true
            }
        }
    }

    return false
}
