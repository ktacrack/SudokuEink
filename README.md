# Sudoku E-Ink HTR

![License](https://img.shields.io/badge/license-MIT-blue)
![Platform](https://img.shields.io/badge/platform-Android-green)
![Language](https://img.shields.io/badge/language-Kotlin-purple)
![Release](https://img.shields.io/github/v/release/serg987/SudokuEinkHTR)
![Downloads](https://img.shields.io/github/downloads/serg987/SudokuEinkHTR/total)

## Screenshots
<p>
  <img src="images/Menu_english.png" width="200">
  <img src="images/Game_english.png" width="200">
  <img src="images/Stats_english.png" width="200">
</p>

## About This Fork

This project is a fork of [SudokuEink by ktacrack](https://github.com/ktacrack/SudokuEink), branching off from version 1.5.1. The first release of this fork is `0.1.0`. 

This fork exists because the HTR (Handwritten Text Recognition) and model-selection changes are broad enough that they are not currently suitable as a small upstream pull request. If useful, individual changes may be proposed upstream later.

The original app is fantastic, but we pursued a different vision and philosophy for e-ink devices that led to many breaking changes. Our primary focus is a **stylus-first, paper-like experience**, allowing handwritten inputs directly on the board and heavily reducing screen refreshes to minimize e-ink ghosting. We are deeply grateful to the original author for their foundation.

*Note: Most code changes in this fork were made with the help of AI.*

## Key Changes & Philosophy

### Stylus-First & Paper-Like Experience
- **Direct Handwriting:** You can write digits directly onto the board. Your original handwritten strokes persist throughout the game instead of immediately converting to a typed font, maintaining a natural paper feel.
- **E-ink Optimized UI:** The UI has been heavily altered to use a high-contrast, strictly black-and-white palette (utilizing the Mudita UI package). This reduces the need for constant screen refreshes and minimizes e-ink ghosting.
- **Flattened Menus:** We flattened many previous distinct menus and moved settings into a central place, freeing the main game screen from side interactions.
- **Action-First Game UX:** The board is cleaner with no background. Button behavior is now "action-first" – you tap a tool (like a digit or eraser), then tap the cell to apply it. We eliminated dynamic highlights to further reduce screen flashes and ghosting.
- **No click ripple/flash:** Removed the default Material ripple app-wide. On e-ink its gray fill sits below the panel's refresh threshold and lingers for about a second as a visible ghost instead of animating away.

### Runs on Non-Onyx Stylus Tablets Too
The app was originally Onyx-only (the Onyx Pen SDK's `TouchHelper` handled stylus capture,
and the e-ink panel's own driver rendered the wet ink). It now also runs on plain LCD
stylus tablets — device-verified on a **Teclast Airpad Pro** (Android 15, non-EMR stylus).
Since there's no EPD driver to draw ink on those screens, the app falls back to capturing
the stylus itself and rendering wet ink in software (`androidx.ink`), matching the approach
already proven in our sibling app, NonogramEink. This is a pure input-layer swap —
gameplay, recognition, and persistence are identical on both device families.

### Enhanced Handwriting Recognition Models
We've integrated new, more precise models for handwriting recognition, providing an improved stylus experience.

## Handwriting recognition models

| Model | Source | License | Distributed in APK? | Notes |
|---|---|---|---|---|
| Original TFLite model | original SudokuEink | MIT / upstream | yes | inherited from upstream |
| ONNX Model | Microsoft | AGPL 3.0 | no / downloaded by user | user must accept terms |
| Google ML Kit | Google | Apache 2.0 | no / downloaded by user | user must accept terms |

## Known Bugs & Issues
- **Dark Mode:** Dark mode is currently not working and the toggle has been disabled in settings.
- **Screen Orientation:** When switching screen orientation (e.g., portrait to landscape), handwritten notes may not align correctly with the cells.
- **Device Support:** Tested on the Onyx Boox Max3 (13.3", e-ink) and a Teclast Airpad Pro
  (LCD, non-EMR stylus). Other non-Onyx stylus tablets should work via the same fallback
  path but are unverified.

---

## Original Core Features Maintained

### 🎮 Gameplay
- **3 difficulty levels:** Easy, Medium, and Hard
- **Smart Sudoku generator** with optimized algorithms
- **Hint system** limited by difficulty (5/3/1)
- **Notes mode** to mark possible numbers
- **Undo moves** with unlimited history

### ⏱️ Timer and Game Management
- **Independent saved games** for each difficulty level
- **Auto-save** and recovery of games in progress

### 📊 Statistics
- **Completed games** and **Best time** per difficulty

## Installation

1. Download the APK from Releases.
2. Install the APK on your e-ink device (tested on Boox) or a stylus-capable LCD tablet
   (tested on Teclast).
3. Open the app, optionally download the enhanced models in the Settings, and start playing!

## License
This project is licensed under the MIT License. See the LICENSE file for details.
You are free to use, modify and distribute this code, as long as you maintain attribution to the original author.

## Acknowledgments
- Original author: [ktacrack](https://github.com/ktacrack)
- Onyx E-ink SDK
- Mudita UI
