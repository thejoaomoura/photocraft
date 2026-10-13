//! Kotlin/Android bindings for the PhotoCraft engine (UniFFI).
//!
//! This file is only the FFI surface: records, the error type and [`PhotoSession`], a thread-safe
//! handle that forwards to [`core::Core`]. Kotlin sees it as
//! `ai.storyteller.photocraft.engine.PhotoSession` (see `uniffi.toml` and `README.md`).
//!
//! Calls are synchronous and can take a while on large documents (open, export, heavy filters):
//! call them from a background dispatcher (`Dispatchers.Default`), never the main thread.

use std::sync::{Mutex, PoisonError};

mod core;

pub use core::{CORE_COMMANDS, DEFAULT_MAX_PIXELS, MAX_PREVIEW_SIDE};

uniffi::setup_scaffolding!();

/// Errors surfaced to Kotlin as `MobileException` subclasses.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum MobileError {
    #[error("no document is open")]
    NoDocument,
    #[error("{message}")]
    InvalidInput { message: String },
    #[error("{width}×{height} is larger than the {max_pixels}-pixel limit of this device")]
    TooLarge { width: u32, height: u32, max_pixels: u64 },
    #[error("{message}")]
    Io { message: String },
    #[error("{message}")]
    Command { message: String },
}

/// The active document at a glance.
#[derive(Debug, Clone, uniffi::Record)]
pub struct DocInfo {
    pub name: String,
    pub width: u32,
    pub height: u32,
    pub layer_count: u32,
    /// Increments on every change: re-render only when it moves.
    pub revision: u64,
    /// Changed since it was opened or last saved as `.pcraft`/PSD.
    pub dirty: bool,
    pub can_undo: bool,
    pub can_redo: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Opened {
    pub info: DocInfo,
    /// What the import could not keep (unsupported PSD features, missing fonts, …).
    pub warnings: Vec<String>,
}

/// Straight-alpha RGBA8, row-major (`width * height * 4` bytes). In Kotlin, wrap with
/// `ByteBuffer.wrap(pixels)` and `Bitmap.copyPixelsFromBuffer` on an `ARGB_8888` bitmap
/// (premultiply first if the image has transparency).
#[derive(Debug, Clone, uniffi::Record)]
pub struct RenderedImage {
    pub width: u32,
    pub height: u32,
    pub pixels: Vec<u8>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Encoded {
    pub bytes: Vec<u8>,
    /// What the format could not keep (e.g. layers flattened by JPEG).
    pub warnings: Vec<String>,
}

/// One PhotoCraft session: the open documents, their history and the tool state.
#[derive(uniffi::Object)]
pub struct PhotoSession {
    core: Mutex<core::Core>,
}

impl PhotoSession {
    fn lock(&self) -> std::sync::MutexGuard<'_, core::Core> {
        // A panic caught by UniFFI must not take every later call down with it.
        self.core.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

#[uniffi::export]
impl PhotoSession {
    #[uniffi::constructor]
    pub fn new() -> Self {
        Self { core: Mutex::new(core::Core::default()) }
    }

    /// Largest document this session opens or creates, in pixels (default 36 MP).
    pub fn set_max_pixels(&self, max_pixels: u64) {
        self.lock().set_max_pixels(max_pixels);
    }

    /// Decode a file's bytes (read through the Storage Access Framework) and make it active.
    /// `name` is the display name; its extension helps detect the format.
    pub fn open(&self, name: String, bytes: Vec<u8>) -> Result<Opened, MobileError> {
        self.lock().open(&name, &bytes)
    }

    pub fn new_document(&self, width: u32, height: u32) -> Result<DocInfo, MobileError> {
        self.lock().new_document(width, height)
    }

    /// Run an engine command by id (see [`core_commands`]) with JSON params; returns JSON.
    pub fn execute(&self, id: String, params_json: String) -> Result<String, MobileError> {
        self.lock().execute(&id, &params_json)
    }

    pub fn is_enabled(&self, id: String) -> bool {
        self.lock().is_enabled(&id)
    }

    /// Why a command can't run now (`null` when it can).
    pub fn disabled_reason(&self, id: String) -> Option<String> {
        self.lock().disabled_reason(&id)
    }

    pub fn undo(&self) -> bool {
        self.lock().undo()
    }

    pub fn redo(&self) -> bool {
        self.lock().redo()
    }

    pub fn info(&self) -> Result<DocInfo, MobileError> {
        self.lock().info()
    }

    pub fn document_count(&self) -> u32 {
        self.lock().document_count()
    }

    pub fn set_active(&self, index: u32) -> bool {
        self.lock().set_active(index)
    }

    pub fn close_active(&self) -> bool {
        self.lock().close_active()
    }

    /// The composite scaled to fit `max_side` (0 = full size, capped at 4096).
    pub fn render(&self, max_side: u32) -> Result<RenderedImage, MobileError> {
        self.lock().render(max_side)
    }

    /// Encode the active document (`pcraft`, `psd`, `png`, `jpg`, `webp`, `tiff`, …).
    pub fn export(&self, format: String, jpeg_quality: Option<u8>) -> Result<Encoded, MobileError> {
        self.lock().export(&format, jpeg_quality)
    }
}

impl Default for PhotoSession {
    fn default() -> Self {
        Self::new()
    }
}

/// The command ids the lightweight app exposes.
#[uniffi::export]
pub fn core_commands() -> Vec<String> {
    CORE_COMMANDS.iter().map(|s| (*s).to_string()).collect()
}

/// The PhotoCraft version this library was built from.
#[uniffi::export]
pub fn engine_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}
