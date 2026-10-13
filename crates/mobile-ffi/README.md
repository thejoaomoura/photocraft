# photocraft-mobile-ffi

Kotlin/Android bindings ([UniFFI](https://mozilla.github.io/uniffi-rs/)) over the PhotoCraft
engine, for the lightweight mobile app. The Kotlin UI (Jetpack Compose) handles the screen,
gestures, the soft keyboard and file access (Storage Access Framework); this crate turns bytes into
documents, runs engine commands and renders the composite.

## API (Kotlin: `ai.storyteller.photocraft.engine`)

| Call | What it does |
|---|---|
| `PhotoSession()` | A session: open documents, history, tool state |
| `open(name, bytes)` | Decode PSD, JPEG, PNG, HEIC, WebP, TIFF, camera raw or `.pcraft` and make it active |
| `newDocument(w, h)` | A blank document |
| `execute(id, paramsJson)` | Run any engine command (`layer.new.layer`, `select.rect`, `paint.stroke`, …); returns JSON |
| `isEnabled(id)`, `disabledReason(id)` | Menu/button enablement |
| `undo()`, `redo()` | History |
| `info()` | Name, size, layer count, `revision`, `dirty`, `canUndo`/`canRedo` |
| `render(maxSide)` | Composite as RGBA8 scaled to fit `maxSide` (0 = full, capped at 4096) |
| `export(format, jpegQuality)` | Encode as `pcraft`, `psd`, `png`, `jpg`, `webp`, `tiff`, … |
| `setMaxPixels(n)` | Size cap for open/new (default 36 MP) |
| `coreCommands()` | The command ids the lightweight app exposes (all registered in the engine; tested) |

Params of every command: `photocraft-cli commands` or `docs/control-protocol.md`.

Calls are synchronous: run them on `Dispatchers.Default`. Errors arrive as `MobileException`
(`NoDocument`, `InvalidInput`, `TooLarge`, `Io`, `Command`); a Rust panic becomes an
`InternalException` and the session stays usable.

## Build

Prerequisites: Rust (stable), the `aarch64-linux-android` and `x86_64-linux-android` targets,
[`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk), and an Android NDK (`ANDROID_NDK_HOME`).

```bash
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk
```

The Android app in [`android/`](../../android) builds this crate itself: its `preBuild` runs
`cargo ndk` (the `.so` into `app/src/main/jniLibs`) and `uniffi-bindgen` (the Kotlin bindings into
`app/src/generated/kotlin`). See [`android/README.md`](../../android/README.md).

By hand, the `.so` for a phone (arm64) and the emulator (x86_64):

```bash
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build -p photocraft-mobile-ffi --release
```

The Kotlin bindings, generated from a host build of the same library:

```bash
cargo build -p photocraft-mobile-ffi --lib
cargo run -p photocraft-mobile-ffi --features bindgen --bin uniffi-bindgen -- generate --library target/debug/photocraft_mobile.dll --language kotlin --out-dir android/app/src/generated/kotlin
```

(`libphotocraft_mobile.so` on Linux, `libphotocraft_mobile.dylib` on macOS.) The generated code
needs JNA on the Android side: `implementation("net.java.dev.jna:jna:5.17.0@aar")`.

Error variants carry their text in `reason`, not `message`: a `message` field would clash with
Kotlin's `Throwable.message`.

## Layering and safety

L6, beside `ui-egui` and `automation`: depends on the engine, never on egui. UniFFI's scaffolding
is `unsafe` FFI glue, so this crate can't inherit `unsafe_code = "forbid"`; all hand-written logic
is in `src/core.rs`, which forbids `unsafe` and denies `unwrap`/`expect`/`panic`.
