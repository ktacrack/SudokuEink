package io.github.serg987.sudokueinkhtr

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.util.Log
import android.view.SurfaceView
import androidx.compose.runtime.mutableStateOf
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode

/**
 * Owns the raw-ink flush lifecycle for [InlineDrawingCanvas]: clearing the Onyx raw-ink
 * overlay and repainting the [SurfaceView] with the committed strokes, without ever
 * toggling `TouchHelper.setRawDrawingEnabled` to do it. See AGENTS.md "Clearing Ink" for
 * why that toggle is banned: it loses the next stroke's ink start (lazy scribble-mode
 * re-entry) and is a confirmed EPD-driver freeze trigger on Onyx devices — see
 * NonogramEink's freeze investigation
 * (`NonogramEink/NonogramEink/AGENTS.md` → "System-wide ANR / device freeze"), which this
 * flush pattern was ported from. The repaint bracket itself follows the Onyx SDK's own
 * scribble demos (`OnyxAndroidDemo` → `scribble/request/PartialRefreshRequest.java` /
 * `RendererToScreenRequest.java`): `setViewDefaultUpdateMode(surfaceView,
 * HAND_WRITING_REPAINT_MODE)` → `lockCanvas()` → draw → `enablePost(surfaceView, 1)`
 * immediately before `unlockCanvasAndPost` → `resetViewUpdateMode(surfaceView)`.
 *
 * THREADING: [isPenDown]/[isPenActive] are `@Volatile` because `RawInputCallback` methods
 * run on the Onyx SDK's private background executor, not the main thread. Code on that
 * thread may only flip these flags and call [scheduleFlush]/[cancelPendingFlush] (which
 * just `post`/`removeCallbacks` a `Runnable` on the `SurfaceView` — safe from any thread).
 * All actual rendering happens inside [flushNow], which only ever runs as that posted
 * runnable, i.e. on the main thread.
 */
class InkFlushController(
    private val strokesProvider: () -> Map<Pair<Int, Int>, List<List<DrawingPoint>>>,
    private val strokeWidthProvider: () -> Float,
) {
    companion object {
        private const val TAG = "SudoFlush"

        /** Delay after a content commit (digit recognized, cell cleared) before the flush runs. */
        const val COMMIT_FLUSH_DELAY_MS = 50L

        /**
         * Structural safety net armed on every pen-up, regardless of what else happens.
         * Sits above the 500 ms recognition timeout so the normal path's own
         * `scheduleFlush(reason, COMMIT_FLUSH_DELAY_MS)` calls fire first; this one only
         * matters if some other path is discarded/short-circuited before it can re-schedule
         * — it guarantees no discard path can strand raw ink or leave the EPD post-lock
         * engaged.
         */
        const val PEN_UP_SAFETY_DELAY_MS = 1000L
    }

    @Volatile
    var isPenDown: Boolean = false

    @Volatile
    var isPenActive: Boolean = false
        set(value) {
            field = value
            // mutableStateOf writes are safe from any thread (Compose's snapshot system
            // handles it); this is the one piece of state this controller exposes to
            // Compose (the game timer holds its display while true — see GameScreen.kt).
            if (penActiveState.value != value) penActiveState.value = value
        }

    /** Main-thread-safe mirror of [isPenActive] for Compose readers (the game timer). */
    val penActiveState = mutableStateOf(false)

    /** Set once the composable creates the SurfaceView; null before first layout. */
    var surfaceView: SurfaceView? = null

    private val flushRunnable = Runnable { flushNow() }

    fun cancelPendingFlush(reason: String) {
        surfaceView?.removeCallbacks(flushRunnable)
        Log.d(TAG, "cancel flush ($reason) isPenDown=$isPenDown")
    }

    fun scheduleFlush(reason: String, delayMs: Long) {
        val view = surfaceView ?: return
        view.removeCallbacks(flushRunnable)
        Log.d(TAG, "arm flush ($reason) delay=$delayMs isPenDown=$isPenDown")
        view.postDelayed(flushRunnable, delayMs)
    }

    private fun flushNow() {
        val view = surfaceView ?: return
        if (isPenDown) {
            // The pen came back down before the settle delay elapsed. Whichever begin*
            // callback caused this already cancelled us; do nothing here and rely on the
            // matching end* callback to re-arm — never self-repost, or a fast back-to-back
            // pen-down/up could interleave two live timers.
            Log.d(TAG, "flush bailed: isPenDown (awaiting pen-up re-arm)")
            return
        }
        try {
            val holder = view.holder
            EpdController.setViewDefaultUpdateMode(view, UpdateMode.HAND_WRITING_REPAINT_MODE)
            val canvas = holder.lockCanvas()
            if (canvas != null) {
                try {
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    drawStrokes(canvas)
                } finally {
                    // Must run before unlockCanvasAndPost, every time: from the first raw
                    // pen-down the EPD driver stops posting window updates to the panel at
                    // all until enablePost re-enables it (undocumented post-lock, see
                    // NonogramEink AGENTS.md). Omitting this silently drops this repaint.
                    EpdController.enablePost(view, 1)
                    holder.unlockCanvasAndPost(canvas)
                }
            }
            EpdController.resetViewUpdateMode(view)
        } catch (t: Throwable) {
            // Non-Onyx device: EpdController calls are silent no-ops there, so this is a
            // real failure only on Onyx hardware. lockCanvas/unlockCanvasAndPost above (if
            // reached) already redrew the strokes regardless — only the raw-ink-overlay
            // clear may be missing, which the next flush corrects.
            Log.w(TAG, "flush repaint bracket failed", t)
        }
        isPenActive = false
        Log.d(TAG, "flush complete")
    }

    private fun drawStrokes(canvas: Canvas) {
        val paint = Paint().apply {
            color = Color.BLACK
            strokeWidth = strokeWidthProvider()
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }
        for ((_, strokes) in strokesProvider()) {
            strokes.forEach { list ->
                if (list.isNotEmpty()) {
                    val path = Path()
                    var prePoint = list[0]
                    path.moveTo(prePoint.x, prePoint.y)
                    for (i in 1 until list.size) {
                        val point = list[i]
                        path.quadTo(prePoint.x, prePoint.y, point.x, point.y)
                        prePoint = point
                    }
                    canvas.drawPath(path, paint)
                }
            }
        }
    }

    /**
     * Plain redraw of the persisted strokes with NO EpdController involvement — for when
     * the underlying Surface has just been (re)created (screen entry, return from
     * background). A fresh surface has no raw-ink overlay to clear, so the repaint
     * bracket buys nothing here — and running it inside the surface-creation/resume
     * transition (next to `openRawDrawing`'s own scribble init) wedges the driver's
     * scribble render channel: wet ink stops rendering live and pen input degrades to
     * flashing normal-mode region updates for the rest of the session (device-verified
     * 2026-07-20, the first `scheduleFlush("surfaceCreated")` attempt). Same doctrine as
     * NonogramEink's deaf-pen-session fix: keep EPD/driver IPCs out of transitions.
     * [flushNow] stays the only path that clears raw ink.
     */
    fun redrawStrokes(reason: String) {
        val view = surfaceView ?: return
        try {
            val holder = view.holder
            val canvas = holder.lockCanvas() ?: return
            try {
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                drawStrokes(canvas)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            Log.d(TAG, "plain redraw ($reason)")
        } catch (t: Throwable) {
            Log.w(TAG, "plain redraw ($reason) failed", t)
        }
    }

    /** Call from the composable's teardown, alongside `touchHelper?.closeRawDrawing()`. */
    fun reset() {
        surfaceView?.removeCallbacks(flushRunnable)
        isPenDown = false
        isPenActive = false
    }
}
