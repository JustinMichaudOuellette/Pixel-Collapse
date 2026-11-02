# Pixel Collapse

An Android pixel-art editor with a wave-function-collapse generator in the middle of it. You draw a small
pattern with a finger, and the app tiles it into a finished picture that fills the screen — then you save it,
share it, or change the drawing and get a new one.

The rules are the drawing itself: every 3x3 window of it becomes a pattern, and the solver may only place
patterns whose neighbouring pixels agree, so the picture is made of the drawing's own shapes and never of
anything invented.

## What it does

* **Draw** with paint, fill, replace-colour, eyedropper, line, rectangle, ellipse, select-and-move and
  duplicate tools, plus undo/redo, mirror, rotate and clear. A long press on any tool explains it.
* **Generate** by tapping the drawing: a seeded collapse fills a grid of cells sized by the preview
  resolution (38x68 at the default 1x, 152x270 at 4x, 9:16). Tapping the preview asks for the next variation
  of the same drawing.
* **Keep the border** — with real edges on, the drawing's outer row and column has to land on the picture's
  border, and the grid marks the band those cells sit in. If no tiling can hold that rule the picture is
  generated without it and says so in two words: *Edges relaxed*.
* **Wrap the edges** instead: the drawing is sampled as a torus, so its border is not a border at all and the
  pattern runs off one side and back on the other.
* **Mirror every pattern** left to right, top to bottom, or both — as well as offering them as drawn — which
  gives the solver more shapes to arrange, and so more possible pictures from the same drawing.
* **Pick the output**: shape (9:16, 3:4, 1:1, 4:3, 16:9), the size the picture is generated at (1x, 2x, 4x,
  shown in pixels), and the size it is saved at (1x to 16x, from 304x540 up to 4864x8640).
* **Save and share** through MediaStore and a FileProvider. A save says what it did in a snackbar with a
  *View* button that opens the file it just wrote.
* **Come back to it**: the drawing, seed and settings are saved when the app goes to the background and
  restored on the next launch.
* **Stay out of the picture's way**: the app is dark and black and white, whatever the system is set to, so the
  drawing's colours are the only colour on screen.

## How it works

```
drawing (cells) ─► 3x3 window tally ─► packed tile set ─► native solver ─► solved cells ─► bitmap
                                                AC-3 propagation, bitset domains,
                                                two workers racing on one model
```

* [`WindowTally`](app/src/main/java/ca/justinmo/wfcbitmap/WindowTally.kt) counts the drawing's 3x3 windows
  incrementally, so an edit only re-tallies the nine windows around each changed pixel.
* [`wfc.c`](app/src/main/cpp/wfc.c) is the solver: plain C, no C++ runtime linked (`ANDROID_STL=none`), with
  tile domains packed into bitsets and propagation to a fixed point.
* [`StrictSolverPortfolio`](app/src/main/java/net/akachaki/processing/engine/wavefunctioncollapse/bitmap/StrictSolverPortfolio.kt)
  runs several workers on the same model and takes the first that finishes.
* [`WfcGenerator`](app/src/main/java/ca/justinmo/wfcbitmap/WfcGenerator.kt) owns the whole pipeline, the
  per-drawing caches, and the two-attempt strategy: the edge rule first with a budget of four times the last
  solve, then, if it cannot be held, the same drawing relaxed with a much longer deadline.
* **The editor** is split by what each part is for, so every file can be read on its own. The Compose code
  lives in the `ui` package:
  [`PixelEditorScreen`](app/src/main/java/ca/justinmo/wfcbitmap/ui/PixelEditorScreen.kt) holds the screen, the
  output menu and its size rows; [`PixelCanvas`](app/src/main/java/ca/justinmo/wfcbitmap/ui/PixelCanvas.kt) the
  drawing surface with its zoom, gestures and the marked edge band;
  [`EditorTools`](app/src/main/java/ca/justinmo/wfcbitmap/ui/EditorTools.kt) the tool strip and the shapes the
  drawing tools fill; [`ColorToolbar`](app/src/main/java/ca/justinmo/wfcbitmap/ui/ColorToolbar.kt) the palette
  and its swatches; [`WfcPreview`](app/src/main/java/ca/justinmo/wfcbitmap/ui/WfcPreview.kt) the live preview and
  its loading bar; [`EditorChrome`](app/src/main/java/ca/justinmo/wfcbitmap/ui/EditorChrome.kt) the measurements
  and colours they share; [`WFCBitmapTheme`](app/src/main/java/ca/justinmo/wfcbitmap/ui/theme/Theme.kt) the one
  colour scheme, black surfaces and white ink. What the UI acts on stays outside it:
  [`PixelGridState`](app/src/main/java/ca/justinmo/wfcbitmap/PixelGridState.kt) the drawing and its undo history,
  [`EditorOutput`](app/src/main/java/ca/justinmo/wfcbitmap/EditorOutput.kt) the shape and the two size settings.
* [`MainActivity`](app/src/main/java/ca/justinmo/wfcbitmap/ui/MainActivity.kt) wires the two together and owns
  the parts the screen must not: generation, saving, sharing, and the session snapshot.

A generation is a *seeded* collapse, so the same drawing on the same grid always produces the same picture —
which is also why an edit that introduces no new pattern hands the previous bitmap straight back, and why a
saved session can be restored without saving a single pixel of the output.

**Sizes.** The grid is what decides the picture's structure, so the size settings only change pixels per
cell. The preview size is the picture's resolution: 1x is the default and solves the fewest cells (the
blockiest picture), 2x solves four times as many, and 4x solves the base 41,040-cell grid (the finest). The
preview always fills its column with the picture, scaled with nearest-neighbour resampling, so the resolution
is visible as the size of its blocks. The save size decides how many pixels each cell of the file gets, and is
independent of the preview.

## Building

Requires an Android SDK with platform 37 and **NDK 27.0.12077973** (pinned in
[`app/build.gradle.kts`](app/build.gradle.kts)).

```bash
./gradlew :app:assembleDebug     # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug      # install on a connected device
```

Min SDK 26, target 37, Kotlin 2.4.21, Compose BOM 2026.09.00, AGP 9.5.0. Only `arm64-v8a` and `x86_64` are
built — phones and the emulator.

## Testing

```bash
./gradlew :app:testDebugUnitTest                     # JVM tests: the solver's Kotlin half, sizes, snapshots
./gradlew :app:connectedDebugAndroidTest             # device tests: the native solver and the Compose UI
```

The JVM suite covers the size arithmetic, the session snapshot format, the window tally, the grid edits and the
solve budget. The device suite covers what needs a real solver and a real canvas: the native solver's output,
the relaxed-edge fallback, the preview sizes, the save snackbar, the selection and duplicate gestures, and the
tools that show state (the wrap button, the edge band on the grid).

Compose tests need the screen awake, so a class that fails with *No compose hierarchies found* is usually a
sleeping phone rather than a broken test. Single classes are quicker to iterate on:

```bash
adb shell am instrument -w -e class ca.justinmo.wfcbitmap.SelectionToolTest \
  ca.justinmo.wfcbitmap.test/androidx.test.runner.AndroidJUnitRunner
```

## Project layout

| Path | What is in it |
| --- | --- |
| `app/src/main/cpp/` | The wave-function-collapse solver in C and its JNI bridge |
| `app/src/main/java/ca/justinmo/wfcbitmap/` | The editor's logic: drawing state, output settings, generator, saving, session snapshot |
| `app/src/main/java/ca/justinmo/wfcbitmap/ui/` | Everything Compose: the screen, canvas, tools, palette, preview, the activity |
| `app/src/main/java/net/akachaki/...` | The generic WFC engine: cells, tile solvers, the solver portfolio |
| `app/src/test/` | JVM tests |
| `app/src/androidTest/` | Device tests, including the solver benchmarks |
| `design/` | The script and candidates used to pick the launcher icon out of generated pictures |

## Roadmap

- [ ] **Pixel-art scaling algorithm** — a dedicated upscaler (EPX/hqx/xBR family) for the saved file, so a
  bigger save size is a rounder piece of pixel art rather than a copy of the same blocks.
- [ ] **GIF output** — export an animated tiling: the same drawing, scrolling one cell at a time, written as a
  looping GIF.
- [ ] **Video output** — record the same kind of motion to MP4 with `MediaCodec`, at a speed and length the
  person picks.
- [ ] **3D rendering output** — stand the picture up as a solid scene, one block per cell, and export it as a
  rotating shot through the video output above or as a model file another tool can open.
- [ ] **Input Photo** — input a photo, downscale it, posterize it and use it as a pattern.
- [ ] **Group Pixels** — allow to group pixels 2x2, 3x3, 4x4, etc. So that it acts like a tilemap.

## License

No licence has been chosen yet. Until one is added, all rights are reserved by the author.
