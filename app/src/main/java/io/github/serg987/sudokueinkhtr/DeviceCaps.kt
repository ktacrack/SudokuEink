package io.github.serg987.sudokueinkhtr

import android.os.Build

/**
 * Device capability split for the two rendering worlds this app runs in.
 *
 * On Onyx/Boox e-ink devices the pen pipeline is hardware-assisted end to end: the Onyx
 * TouchHelper raw channel captures strokes AND the EPD driver renders the wet ink directly
 * into the framebuffer ([InlineDrawingCanvas] never draws it itself), and EpdController IPCs
 * (repaint/GC refresh modes) are functional.
 *
 * On everything else (device-verified on a Teclast Airpad Pro, Android 15 LCD): the Onyx
 * SDK's capture *happens* to work through an undocumented touch-event fallback, but nothing
 * ever renders (there is no EPD driver), and every EpdController call is a silent no-op. The
 * same fallback also eats the view's stylus AND finger `MotionEvent`s, which breaks the
 * action-first UI (digit/erase/notes buttons rely on a finger tap on a cell). So on non-Onyx
 * devices we skip the Onyx pipeline entirely: no `TouchHelper` is ever created, stylus
 * strokes are captured from plain MotionEvents, and wet ink is rendered in software via
 * androidx.ink (InProgressStrokesView low-latency front-buffered rendering) — ported from
 * NonogramEink's non-Onyx support (`NonogramEink/NonogramEink/AGENTS.md` → "Non-Onyx (LCD)
 * Device Support").
 */
object DeviceCaps {
    val isOnyx: Boolean =
        Build.MANUFACTURER.equals("ONYX", ignoreCase = true) ||
            Build.BRAND.equals("ONYX", ignoreCase = true)
}
