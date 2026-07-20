package io.github.serg987.sudokueinkhtr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    penActiveState: MutableState<Boolean>? = null
) {
    val context = LocalContext.current
    val scaleFactor = AdaptiveSizes.getScaleFactor()

    var paths by remember { mutableStateOf(mutableListOf<List<DrawingPoint>>()) }
    var touchHelper by remember { mutableStateOf<TouchHelper?>(null) }
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }

    var viewWidth by remember { mutableIntStateOf(0) }
    var viewHeight by remember { mutableIntStateOf(0) }

    var lastStrokeTime by remember { mutableLongStateOf(0L) }
    var isErasing by remember { mutableStateOf(false) }

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

    DisposableEffect(htrModel) {
        onDispose {
            controller.reset()
            touchHelper?.closeRawDrawing()
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

                        clearRunnables[cellKey]?.let { surfaceView?.removeCallbacks(it) }
                        val r = Runnable {
                            Log.d("InlineDrawingCanvas", "currentOnClearCell executing safely after debounce (gesture)")
                            currentOnClearCell(row, col)
                        }
                        clearRunnables[cellKey] = r
                        surfaceView?.postDelayed(r, 600)

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

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                setZOrderOnTop(true)
                holder.setFormat(PixelFormat.TRANSPARENT)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        val canvas = holder.lockCanvas()
                        canvas?.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                        if (canvas != null) {
                            holder.unlockCanvasAndPost(canvas)
                        }
                    }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                    override fun surfaceDestroyed(holder: SurfaceHolder) {}
                })

                setOnTouchListener { _, event ->
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

                surfaceView = this
                controller.surfaceView = this
            }
        },
        update = { view ->
            if (touchHelper == null) {
                view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        v: View, left: Int, top: Int, right: Int, bottom: Int,
                        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                    ) {
                        view.removeOnLayoutChangeListener(this)
                        viewWidth = right - left
                        viewHeight = bottom - top

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
                                    if (!isErasing) lastStrokeTime = System.currentTimeMillis()
                                }
                            }
                            override fun onRawDrawingTouchPointMoveReceived(touchPoint: TouchPoint) {}
                            override fun onRawDrawingTouchPointListReceived(touchPointList: TouchPointList) {
                                // Copy off the SDK's recycled list on this thread; the
                                // Compose state append itself is posted to main.
                                val points = touchPointList.points.map { DrawingPoint(it.x, it.y, it.timestamp) }
                                view.post {
                                    if (!isErasing) {
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
                                    isErasing = true
                                    paths = mutableListOf()
                                    lastStrokeTime = 0L
                                }
                            }
                            override fun onEndRawErasing(b: Boolean, touchPoint: TouchPoint) {
                                controller.isPenDown = false
                                controller.scheduleFlush("endErase", InkFlushController.COMMIT_FLUSH_DELAY_MS)
                                view.post {
                                    val col = (touchPoint.x / (viewWidth / 9f)).toInt().coerceIn(0, 8)
                                    val row = (touchPoint.y / (viewHeight / 9f)).toInt().coerceIn(0, 8)
                                    Log.d("InlineDrawingCanvas", "onEndRawErasing. row=$row, col=$col")

                                    val cellKey = Pair(row, col)
                                    val newStrokes = currentInkStrokes.toMutableMap()
                                    newStrokes.remove(cellKey)
                                    onInkStrokesChanged(newStrokes)

                                    clearRunnables[cellKey]?.let { surfaceView?.removeCallbacks(it) }
                                    val r = Runnable {
                                        Log.d("InlineDrawingCanvas", "currentOnClearCell executing safely after debounce (onEndRawErasing)")
                                        // Invoking the current lambda using rememberUpdatedState to avoid stale boardState
                                        currentOnClearCell(row, col)
                                    }
                                    clearRunnables[cellKey] = r
                                    // 600ms delay ensures native e-ink hardware erase completely finishes before Android renders
                                    surfaceView?.postDelayed(r, 600)

                                    paths = mutableListOf()
                                    lastStrokeTime = 0L
                                    surfaceView?.postDelayed({ isErasing = false }, 500)
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
