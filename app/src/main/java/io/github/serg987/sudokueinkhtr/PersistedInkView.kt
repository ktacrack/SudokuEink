package io.github.serg987.sudokueinkhtr

import android.content.Context
import android.graphics.Canvas
import android.view.View

/**
 * Non-Onyx (LCD) counterpart of the Onyx `SurfaceView`'s committed-ink layer.
 *
 * On Onyx, [InkFlushController.flushNow] draws the persisted strokes straight into the
 * `SurfaceView`'s locked canvas via the EPD repaint bracket — the native EPD driver renders
 * the *wet* ink separately, so that surface only ever needs to hold committed strokes.
 * There is no EPD driver on non-Onyx devices, so this plain `View` takes over that half:
 * it draws only committed strokes (via the same [drawInkStrokes] routine), and a stacked
 * `InProgressStrokesView` (see [InlineDrawingCanvas]) handles the live wet ink on top of it.
 *
 * A plain `View` rather than a second `SurfaceView`: the `InProgressStrokesView` used for
 * wet ink is itself a front-buffered `SurfaceView`, and two "on top" surfaces in the same
 * window have no defined relative Z-order. A regular `View` inside the `AndroidView` draws
 * above `SudokuBoard` by ordinary Compose z-ordering, with no surface juggling needed.
 */
class PersistedInkView(context: Context) : View(context) {
    var strokesProvider: (() -> Map<Pair<Int, Int>, List<List<DrawingPoint>>>)? = null
    var strokeWidthProvider: (() -> Float)? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val strokes = strokesProvider?.invoke() ?: return
        val strokeWidth = strokeWidthProvider?.invoke() ?: return
        drawInkStrokes(canvas, strokes, strokeWidth)
    }
}
