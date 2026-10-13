# PhotoCraft for Android (lite)

A lightweight Android app over the PhotoCraft engine: a Jetpack Compose UI on top of
[`crates/mobile-ffi`](../crates/mobile-ffi), the Rust engine compiled for Android and reached
through UniFFI. File access, gestures and the screen are Android's; documents, layers,
adjustments, PSD and rendering are the same Rust code as the desktop app.

## What it does

- Open photos and PSDs (Storage Access Framework, or "Open with" / "Edit" from the gallery)
- View: two fingers pan and zoom (up to 32×) with any tool; zoomed in, the visible area is
  re-rendered at screen resolution; double-tap toggles fit/3× with the Move tool
- **Layers panel**: thumbnails, visibility, opacity, blend mode, reorder, duplicate, merge down,
  delete, new layer; adjustment layers reopen in the Properties panel
- **Brush and Eraser** with pen pressure, size, hardness, opacity, colour swatches and an
  Eyedropper
- **Selections**: rectangular and elliptical marquee, lasso, Magic Wand (tolerance), Select
  Subject, all / inverse / deselect, with New / Add / Subtract / Intersect; the area outside the
  selection is dimmed from the real mask
- **Adjustment layers**: Curves (per channel, over the histogram), Levels, Brightness/Contrast,
  Hue/Saturation, Vibrance, Exposure, Black & White; made with a selection, the selection becomes
  the layer mask. Each edit session is one history step; Cancel removes it
- **Filters** (inside the selection when there is one): Gaussian, Motion and Surface Blur, Unsharp
  Mask, Smart Sharpen, Reduce Noise, Add Noise, Median, previewed on slider release
- **Crop** with handles, rule-of-thirds grid and Free / Original / 1:1 / 4:3 / 3:4 / 16:9 / 9:16
- Rotate 90°/180°, flip, undo / redo, flatten
- Export JPEG, PNG, PSD or `.pcraft`
- Autosave a few seconds after each edit and when the app goes to the background, restored on
  the next launch
- English and Brazilian Portuguese

Limits of this version: documents up to 36 MP; the base preview is the CPU composite scaled to
the screen (≤ 2048 px); a brush stroke is sent to the engine when the finger lifts (drawn as an
approximate overlay until then).

## Build

Prerequisites: JDK 17, the Android SDK (platform 36), an NDK, Rust with the Android targets and
`cargo-ndk` (see [`crates/mobile-ffi/README.md`](../crates/mobile-ffi/README.md)).

```bash
cd android
./gradlew assembleDebug
```

`preBuild` compiles the Rust library (release, LTO: the first build takes a while) and generates
the Kotlin bindings. Gradle properties:

| Property | Default | Meaning |
|---|---|---|
| `photocraft.abis` | `arm64-v8a` | ABIs to build, e.g. `arm64-v8a,x86_64` for the emulator too |
| `photocraft.skipRust` | `false` | Use the `.so` and bindings already in `app/src/main/jniLibs` and `app/src/generated/kotlin` |

CI (`.github/workflows/android.yml`) builds the Rust library once, then the APKs with
`-Pphotocraft.skipRust=true`; the `photocraft-android-apk` artifact has the debug and release APKs
(release is signed with the debug key until a release key is set up).

## Layout

| Path | What |
|---|---|
| `app/src/main/java/.../MainActivity.kt` | Entry point, "Open with" intents, autosave on stop |
| `app/src/main/java/.../editor/EditorViewModel.kt` | Owns the `PhotoSession`; every engine call on one background thread |
| `app/src/main/java/.../editor/Adjustments.kt` | The adjustment layers and export formats offered |
| `app/src/main/java/.../ui/` | Compose screens: editor, zoomable canvas, adjustment panel |
| `app/src/generated/kotlin/` | UniFFI bindings (generated, not committed) |
| `app/src/main/jniLibs/` | `libphotocraft_mobile.so` per ABI (built, not committed) |
