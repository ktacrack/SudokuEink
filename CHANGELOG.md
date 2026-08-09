# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [0.2.0] - 2026-07-21

### Added
- **Non-Onyx (LCD) stylus support**, ported from NonogramEink and device-verified on a
  Teclast Airpad Pro: no `TouchHelper` is created on non-Onyx devices (its undocumented
  fallback ate the stylus and finger `MotionEvent`s), so wet ink is now captured from plain
  `MotionEvent`s and rendered live with an `androidx.ink` overlay, and the action-first
  digit/erase/notes buttons (which rely on a finger tap on a cell) work again.

### Fixed
- **Reset no longer restores a saved game's handwriting.** It was reassigning
  `inkStrokes = initialStrokes` (the strokes loaded from the save file) instead of clearing
  them, so after an app restart, Reset silently un-erased ink it should have wiped. Reset
  now clears ink on both device families, matching what it already does to the board.
- **Removed the click ripple/gray-flash** on every button and board cell app-wide — on
  e-ink its fill sits below the refresh threshold and lingers as a ~1s ghost instead of
  animating away.

### Changed
- **Raw-ink flush pipeline rewritten** to stop toggling `TouchHelper.setRawDrawingEnabled`
  when clearing handwritten ink. That toggle loses the next stroke's ink start (lazy
  scribble-mode re-entry) and is a confirmed EPD-driver freeze trigger on Onyx devices
  under sustained pen use. Ink is now cleared via a repaint bracket
  (`EpdController.enablePost` + `HAND_WRITING_REPAINT_MODE`, ported from NonogramEink's
  freeze investigation and precedented in Onyx's own SDK demos), gated so it never runs
  while the pen is physically down. `RawInputCallback` methods now do only trivial work on
  the Onyx SDK's background thread; all app-state updates are posted to the main thread.
- **Game timer display holds steady while the pen is active** (pen-down through the
  trailing ink flush, roughly per digit written) instead of recomposing every second
  regardless — a periodic recomposition racing a pen-down could eat the wet ink of the
  digit currently being written. Elapsed-time accounting itself never stops or drifts;
  only the visible text pauses, then jumps to the correct value once the pen lifts.
- **Main menu warns when the Onyx E-ink refresh mode isn't Normal** (Speed/A2/X/Regal),
  since only Normal-mode sessions are verified clean against the freeze above. Re-checked
  on every window-focus regain, so switching modes via the system E-ink center modal
  updates the banner without leaving the app.

## [0.1.0] - 2026-06-25

This is the first release of the **Sudoku E-Ink HTR** fork. This project was forked from [SudokuEink by ktacrack](https://github.com/ktacrack/SudokuEink) at version 1.5.1.

### Added
- **Stylus-first gameplay:** Write directly on the board and keep original handwriting throughout the game for a paper-like feel.
- **Advanced Handwriting Recognition Models:** Support for ONNX (AGPL 3.0) and Google ML Kit (Apache 2.0) models. To save APK size and comply with licenses, these models are downloaded on-demand.
- **Action-first interaction:** Select a tool or digit first, then tap the target cell to apply it (one-shot action).
- **Flattened Navigation:** Simplified menus and centralized settings for a less distracting game flow.

### Changed
- **E-ink optimized UI:** Replaced the UI with Mudita UI components for a strict black-and-white, high-contrast experience, removing cell highlights, background patterns, and generic grays to minimize e-ink ghosting.
- Changed app name to "Sudoku E-Ink HTR" and package name to `io.github.serg987.sudokueinkhtr`.
- Removed old upstream changelog history to focus purely on the forked version's trajectory.
- *Note: Most of the code changes in this fork were made with the assistance of AI.*

### Removed
- Dark mode temporarily disabled due to incompatibility with the new high-contrast UI approach.
