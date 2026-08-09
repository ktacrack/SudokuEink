package io.github.serg987.sudokueinkhtr

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.util.Log
import android.view.SurfaceView
import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesView
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode

/**
 * Owns the raw-ink flush lifecycle for [InlineDrawingCanvas]: clearing the wet-ink layer
 * and repainting the committed strokes, without ever toggling
 * `TouchHelper.setRawDrawingEnabled` to do it. See AGENTS.md "Clearing Ink" for why that
 * toggle is banned: it loses the next stroke's ink start (lazy scribble-mode re-entry) and
 * is a confirmed EPD-driver freeze trigger on Onyx devices — see NonogramEink's freeze
 * investigation (`NonogramEink/NonogramEink/AGENTS.md` → "System-wide ANR / device
 * freeze"), which this flush pattern was ported from.
 *
 * Two device pipelines share this controller (see [DeviceCaps]):
 * - **Onyx**: wet ink is rendered natively by the EPD driver; [flushNow] clears it with the
 *   repaint bracket the Onyx SDK's own scribble demos use
 *   (`OnyxAndroidDemo` → `scribble/request/PartialRefreshRequest.java` /
 *   `RendererToScreenRequest.java`): `setViewDefaultUpdateMode(onyxSurface,
 *   HAND_WRITING_REPAINT_MODE)` → `lockCanvas()` → draw → `enablePost(onyxSurface, 1)`
 *   immediately before `unlockCanvasAndPost` → `resetViewUpdateMode(onyxSurface)`.
 * - **Non-Onyx (LCD)**: there is no EPD driver, so wet ink is rendered by an
 *   `InProgressStrokesView` overlay ([inkOverlay]) and committed strokes by a plain
 *   [PersistedInkView] ([persistedView]) underneath it. [flushNow] "clears" the wet layer
 *   by invalidating the persisted view (so committed ink is on screen) and then removing
 *   the settled overlay strokes ([clearSoftwareInk]) — ported from NonogramEink's
 *   `flushHardwareInk` non-Onyx branch.
 *
 * THREADING: [isPenDown]/[isPenActive] are `@Volatile` because on Onyx, `RawInputCallback`
 * methods run on the Onyx SDK's private background executor, not the main thread. Code on
 * that thread may only flip these flags and call [scheduleFlush]/[cancelPendingFlush]
 * (which just `post`/`removeCallbacks` a `Runnable` on [hostView] — safe from any thread).
 * All actual rendering happens inside [flushNow], which only ever runs as that posted
 * runnable, i.e. on the main thread. On non-Onyx, capture already happens on the main
 * thread (`MotionEvent`s), so this is stricter than required there — kept uniform anyway.
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

        /**
         * Non-Onyx only: gap between invalidating [persistedView] (committed strokes) and
         * removing the settled strokes from [inkOverlay] (wet ink). Both layers draw
         * identical black ink, so this small overlap is invisible, but clearing the overlay
         * first would blink the digit off for a frame before the persisted layer catches up.
         */
        const val OVERLAY_CLEAR_DELAY_MS = 50L
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

    /**
     * View used for `postDelayed`/`removeCallbacks` scheduling — the Onyx `SurfaceView` or
     * the non-Onyx `FrameLayout`, whichever the composable created. Set once at first
     * layout; null before then.
     */
    var hostView: View? = null

    /** Onyx only: the `SurfaceView` the EPD repaint bracket locks/draws/posts on. */
    var onyxSurface: SurfaceView? = null

    /** Non-Onyx only: the plain view holding committed strokes. */
    var persistedView: PersistedInkView? = null

    /** Non-Onyx only: the wet-ink overlay. */
    var inkOverlay: InProgressStrokesView? = null

    /**
     * Non-Onyx only: finished-but-not-yet-flushed overlay strokes (the LCD analogue of raw
     * ink still on the e-ink panel). Main thread only — populated by the
     * `InProgressStrokesFinishedListener` registered in [InlineDrawingCanvas].
     */
    val finishedInkIds = mutableSetOf<InProgressStrokeId>()

    private val flushRunnable = Runnable { flushNow() }
    private val overlayClearRunnable = Runnable { clearSoftwareInk() }

    fun cancelPendingFlush(reason: String) {
        hostView?.removeCallbacks(flushRunnable)
        hostView?.removeCallbacks(overlayClearRunnable)
        Log.d(TAG, "cancel flush ($reason) isPenDown=$isPenDown")
    }

    fun scheduleFlush(reason: String, delayMs: Long) {
        val view = hostView ?: return
        view.removeCallbacks(flushRunnable)
        Log.d(TAG, "arm flush ($reason) delay=$delayMs isPenDown=$isPenDown")
        view.postDelayed(flushRunnable, delayMs)
    }

    private fun flushNow() {
        val view = hostView ?: return
        if (isPenDown) {
            // The pen came back down before the settle delay elapsed. Whichever begin*
            // callback caused this already cancelled us; do nothing here and rely on the
            // matching end* callback to re-arm — never self-repost, or a fast back-to-back
            // pen-down/up could interleave two live timers.
            Log.d(TAG, "flush bailed: isPenDown (awaiting pen-up re-arm)")
            return
        }
        if (DeviceCaps.isOnyx) {
            flushOnyx()
        } else {
            flushSoftware(view)
        }
        isPenActive = false
    }

    private fun flushOnyx() {
        val view = onyxSurface ?: return
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
            // Only reachable on Onyx (this function never runs on non-Onyx — see
            // flushNow). lockCanvas/unlockCanvasAndPost above (if reached) already redrew
            // the strokes regardless — only the raw-ink-overlay clear may be missing,
            // which the next flush corrects.
            Log.w(TAG, "flush repaint bracket failed", t)
        }
        Log.d(TAG, "flush complete (epd)")
    }

    /**
     * Non-Onyx: invalidate the persisted-strokes view so the just-committed content is on
     * screen, then remove the settled overlay strokes a beat later ([OVERLAY_CLEAR_DELAY_MS])
     * so the hand-off from wet to committed ink never blinks.
     */
    private fun flushSoftware(view: View) {
        persistedView?.invalidate()
        view.removeCallbacks(overlayClearRunnable)
        view.postDelayed(overlayClearRunnable, OVERLAY_CLEAR_DELAY_MS)
        Log.d(TAG, "flush complete (software)")
    }

    /** Removes every finished (settled) stroke from the software ink overlay, if any. */
    private fun clearSoftwareInk() {
        if (finishedInkIds.isEmpty()) return
        inkOverlay?.removeFinishedStrokes(finishedInkIds.toSet())
        finishedInkIds.clear()
    }

    private fun drawStrokes(canvas: Canvas) {
        drawInkStrokes(canvas, strokesProvider(), strokeWidthProvider())
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
     *
     * Non-Onyx: there's no Surface lifecycle to mirror here (the [PersistedInkView] is a
     * plain `View`) — just invalidate it.
     */
    fun redrawStrokes(reason: String) {
        if (!DeviceCaps.isOnyx) {
            persistedView?.invalidate()
            Log.d(TAG, "plain redraw ($reason) (software)")
            return
        }
        val view = onyxSurface ?: return
        try {
            val holder = view.holder
            val canvas = holder.lockCanvas() ?: return
            try {
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                drawStrokes(canvas)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            Log.d(TAG, "plain redraw ($reason) (epd)")
        } catch (t: Throwable) {
            Log.w(TAG, "plain redraw ($reason) failed", t)
        }
    }

    /** Call from the composable's teardown, alongside `touchHelper?.closeRawDrawing()`. */
    fun reset() {
        hostView?.removeCallbacks(flushRunnable)
        hostView?.removeCallbacks(overlayClearRunnable)
        finishedInkIds.clear()
        isPenDown = false
        isPenActive = false
    }
}

/**
 * Renders the committed (persisted) handwriting strokes into [canvas]. Shared by both
 * device pipelines so committed ink looks identical on either: the Onyx path calls this
 * inside [InkFlushController]'s `lockCanvas` repaint bracket, the non-Onyx path calls it
 * from [PersistedInkView.onDraw].
 */
internal fun drawInkStrokes(
    canvas: Canvas,
    strokes: Map<Pair<Int, Int>, List<List<DrawingPoint>>>,
    strokeWidth: Float,
) {
    val paint = Paint().apply {
        color = Color.BLACK
        this.strokeWidth = strokeWidth
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    for ((_, cellStrokes) in strokes) {
        cellStrokes.forEach { list ->
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
