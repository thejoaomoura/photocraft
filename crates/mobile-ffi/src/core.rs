//! The mobile façade over [`Session`], free of FFI concerns (and of `unsafe`).
//!
//! The Kotlin app drives PhotoCraft the same way the CLI and the control channel do: open bytes,
//! run commands by id with JSON params, render the composite, encode bytes back. File access stays
//! on the Kotlin side (Storage Access Framework), so nothing here touches the file system.
#![forbid(unsafe_code)]
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::unimplemented, clippy::todo, clippy::unreachable)]

use photocraft_doc::Document;
use photocraft_engine::Session;
use serde_json::Value;

use crate::{DocInfo, Encoded, MobileError, Opened, RenderedImage};

/// Largest document the lightweight app opens or creates, in pixels (36 MP: a 24-36 MP camera
/// photo fits, a panorama that would exhaust a phone's memory does not).
pub const DEFAULT_MAX_PIXELS: u64 = 36_000_000;

/// Longest side of a preview when the caller asks for "full size" (`max_side == 0`): mobile GPUs
/// commonly cap textures at 4096 px.
pub const MAX_PREVIEW_SIDE: u32 = 4096;

/// Commands the lightweight app exposes. Every id here is registered in the engine (tested).
pub const CORE_COMMANDS: &[&str] = &[
    // History
    "edit.undo",
    "edit.redo",
    // Document
    "file.new",
    "image.crop",
    "image.imageSize",
    "image.canvasSize",
    "image.imageRotation.90cw",
    "image.imageRotation.90ccw",
    "image.imageRotation.180",
    "image.imageRotation.flipCanvasHorizontal",
    "image.imageRotation.flipCanvasVertical",
    // Layers
    "layer.new.layer",
    "layer.duplicate",
    "layer.delete",
    "layer.mergeDown",
    "layer.flattenImage",
    "layer.select",
    // Name, visibility, opacity, blend mode, locks.
    "layer.setProps",
    // Edit an adjustment layer's settings (the Properties panel).
    "layer.setAdjustment",
    // Adjustment layers (non-destructive)
    "layer.newAdjustmentLayer.brightnessContrast",
    "layer.newAdjustmentLayer.levels",
    "layer.newAdjustmentLayer.curves",
    "layer.newAdjustmentLayer.exposure",
    "layer.newAdjustmentLayer.vibrance",
    "layer.newAdjustmentLayer.hueSaturation",
    "layer.newAdjustmentLayer.blackWhite",
    // Direct adjustments
    "image.adjustments.brightnessContrast",
    "image.adjustments.levels",
    "image.adjustments.curves",
    "image.adjustments.exposure",
    "image.adjustments.vibrance",
    "image.adjustments.hueSaturation",
    // Selection
    "select.all",
    "select.deselect",
    "select.inverse",
    "select.rect",
    "select.lasso",
    "select.magicWand",
    // Painting (Brush and Eraser)
    "paint.stroke",
];

pub struct Core {
    session: Session,
    max_pixels: u64,
}

impl Default for Core {
    fn default() -> Self {
        Self { session: Session::new(), max_pixels: DEFAULT_MAX_PIXELS }
    }
}

impl Core {
    pub fn set_max_pixels(&mut self, max_pixels: u64) {
        self.max_pixels = max_pixels.max(1);
    }

    /// Decode `bytes` (PSD, JPEG, PNG, HEIC, WebP, TIFF, camera raw, `.pcraft`, …; the format is
    /// detected from the content and `name`'s extension) and make it the active document.
    pub fn open(&mut self, name: &str, bytes: &[u8]) -> Result<Opened, MobileError> {
        if bytes.is_empty() {
            return Err(MobileError::InvalidInput { message: format!("`{name}` is empty") });
        }
        let is_pcraft = name.rsplit('.').next().is_some_and(|e| e.eq_ignore_ascii_case(photocraft_format::EXTENSION));
        let (doc, warnings) = if is_pcraft {
            (photocraft_format::load_from_bytes(bytes).map_err(io)?, Vec::new())
        } else {
            let r = photocraft_io::import(name, bytes).map_err(io)?;
            (r.document, r.warnings)
        };
        self.check_size(doc.size.width, doc.size.height)?;
        self.session.add_document(doc, None);
        Ok(Opened { info: self.info()?, warnings })
    }

    /// File › New: a blank RGB 8-bit document.
    pub fn new_document(&mut self, width: u32, height: u32) -> Result<DocInfo, MobileError> {
        self.check_size(width, height)?;
        self.session.execute("file.new", serde_json::json!({ "width": width, "height": height })).map_err(command)?;
        self.info()
    }

    /// Run any engine command by id; `params_json` is a JSON object (empty means `{}`). Returns
    /// the command's JSON result.
    pub fn execute(&mut self, id: &str, params_json: &str) -> Result<String, MobileError> {
        let params: Value = if params_json.trim().is_empty() {
            Value::Object(Default::default())
        } else {
            serde_json::from_str(params_json).map_err(|e| MobileError::InvalidInput { message: format!("bad params JSON for `{id}`: {e}") })?
        };
        if id == "file.new" {
            let dim = |k: &str| params.get(k).and_then(Value::as_u64).map(|v| u32::try_from(v).unwrap_or(u32::MAX));
            if let (Some(w), Some(h)) = (dim("width"), dim("height")) {
                self.check_size(w, h)?;
            }
        }
        let result = self.session.execute(id, params).map_err(command)?;
        serde_json::to_string(&result).map_err(|e| MobileError::Command { message: e.to_string() })
    }

    pub fn is_enabled(&self, id: &str) -> bool {
        self.session.is_enabled(id)
    }

    pub fn disabled_reason(&self, id: &str) -> Option<String> {
        self.session.disabled_reason(id)
    }

    pub fn undo(&mut self) -> bool {
        self.session.undo()
    }

    pub fn redo(&mut self) -> bool {
        self.session.redo()
    }

    pub fn document_count(&self) -> u32 {
        u32::try_from(self.session.documents().len()).unwrap_or(u32::MAX)
    }

    pub fn set_active(&mut self, index: u32) -> bool {
        self.session.set_active(index as usize)
    }

    pub fn close_active(&mut self) -> bool {
        match self.session.active_index() {
            Some(i) => self.session.close(i).is_some(),
            None => false,
        }
    }

    pub fn info(&self) -> Result<DocInfo, MobileError> {
        let st = self.session.active().ok_or(MobileError::NoDocument)?;
        Ok(DocInfo {
            name: st.doc.name.clone(),
            width: st.doc.size.width,
            height: st.doc.size.height,
            layer_count: u32::try_from(st.doc.walk().len()).unwrap_or(u32::MAX),
            revision: st.revision,
            dirty: st.revision != st.saved_revision,
            can_undo: st.history.can_undo(),
            can_redo: st.history.can_redo(),
        })
    }

    /// The composite as straight-alpha RGBA8, scaled to fit `max_side` (0 = full size, capped at
    /// [`MAX_PREVIEW_SIDE`]). Compare [`DocInfo::revision`] to skip re-rendering an unchanged document.
    pub fn render(&self, max_side: u32) -> Result<RenderedImage, MobileError> {
        let doc = self.active_doc()?;
        let side = if max_side == 0 { MAX_PREVIEW_SIDE } else { max_side.min(MAX_PREVIEW_SIDE) };
        let img = photocraft_compose::thumbnail(doc, side);
        Ok(RenderedImage { width: img.width, height: img.height, pixels: img.pixels })
    }

    /// Encode the active document as `format` (an extension: `pcraft`, `psd`, `png`, `jpg`,
    /// `webp`, `tiff`, …). `jpeg_quality` (1-100) applies to JPEG and lossy WebP.
    pub fn export(&mut self, format: &str, jpeg_quality: Option<u8>) -> Result<Encoded, MobileError> {
        let ext = format.trim().trim_start_matches('.').to_ascii_lowercase();
        if ext.is_empty() {
            return Err(MobileError::InvalidInput { message: "no export format given".into() });
        }
        let doc = self.active_doc()?;
        let encoded = if ext == photocraft_format::EXTENSION {
            let opts = photocraft_format::SaveOptions {
                thumbnail: Some(photocraft_compose::thumbnail(doc, 256)),
                composite: Some(photocraft_compose::thumbnail(doc, 1024)),
            };
            Encoded { bytes: photocraft_format::save_to_bytes(doc, &opts).map_err(io)?, warnings: Vec::new() }
        } else {
            let mut opts = photocraft_io::ExportOptions::default();
            if let Some(q) = jpeg_quality {
                let q = q.clamp(1, 100);
                opts.encode.jpeg_quality = q;
                opts.encode.webp_quality = q;
            }
            let r = photocraft_io::export(doc, &ext, &opts).map_err(io)?;
            Encoded { bytes: r.bytes, warnings: r.warnings }
        };
        // `.pcraft` and layered PSD keep everything: the document now matches what was written.
        if matches!(ext.as_str(), "pcraft" | "psd")
            && let Some(st) = self.session.active_mut()
        {
            st.saved_revision = st.revision;
        }
        Ok(encoded)
    }

    fn active_doc(&self) -> Result<&Document, MobileError> {
        self.session.active().map(|st| &*st.doc).ok_or(MobileError::NoDocument)
    }

    fn check_size(&self, width: u32, height: u32) -> Result<(), MobileError> {
        let pixels = u64::from(width).saturating_mul(u64::from(height));
        if width == 0 || height == 0 {
            return Err(MobileError::InvalidInput { message: format!("invalid size {width}×{height}") });
        }
        if pixels > self.max_pixels {
            return Err(MobileError::TooLarge { width, height, max_pixels: self.max_pixels });
        }
        Ok(())
    }
}

fn io(e: impl std::fmt::Display) -> MobileError {
    MobileError::Io { message: e.to_string() }
}

fn command(e: photocraft_engine::EngineError) -> MobileError {
    match e {
        photocraft_engine::EngineError::NoDocument => MobileError::NoDocument,
        e => MobileError::Command { message: e.to_string() },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn png_bytes(w: u32, h: u32) -> Vec<u8> {
        let mut c = Core::default();
        c.new_document(w, h).unwrap();
        c.export("png", None).unwrap().bytes
    }

    #[test]
    fn every_core_command_is_registered() {
        for id in CORE_COMMANDS {
            assert!(photocraft_engine::commands::find(id).is_some(), "`{id}` is not an engine command");
        }
    }

    #[test]
    fn new_edit_undo_render_export() {
        let mut c = Core::default();
        let info = c.new_document(64, 48).unwrap();
        assert_eq!((info.width, info.height), (64, 48));
        let before = info.layer_count;
        c.execute("layer.new.layer", "").unwrap();
        assert_eq!(c.info().unwrap().layer_count, before + 1);
        assert!(c.info().unwrap().can_undo);
        assert!(c.undo());
        assert_eq!(c.info().unwrap().layer_count, before);
        assert!(c.redo());
        c.execute("layer.setProps", r#"{"opacity": 0.5, "blend": "Multiply"}"#).unwrap();
        c.execute("layer.newAdjustmentLayer.hueSaturation", "").unwrap();
        assert_eq!(c.info().unwrap().layer_count, before + 2);

        let img = c.render(32).unwrap();
        assert_eq!((img.width, img.height), (32, 24));
        assert_eq!(img.pixels.len(), 32 * 24 * 4);

        for fmt in ["png", "jpg", "psd", "pcraft"] {
            assert!(!c.export(fmt, Some(90)).unwrap().bytes.is_empty(), "{fmt}");
        }
        assert!(!c.info().unwrap().dirty, "saving as .pcraft marks the document clean");
    }

    #[test]
    fn round_trips_through_open() {
        let mut c = Core::default();
        let opened = c.open("photo.png", &png_bytes(20, 10)).unwrap();
        assert_eq!((opened.info.width, opened.info.height), (20, 10));
        let pcraft = c.export("pcraft", None).unwrap().bytes;
        let again = c.open("copy.pcraft", &pcraft).unwrap();
        assert_eq!((again.info.width, again.info.height), (20, 10));
        assert_eq!(c.document_count(), 2);
        assert!(c.close_active());
        assert_eq!(c.document_count(), 1);
    }

    #[test]
    fn rejects_hostile_input_without_panicking() {
        let mut c = Core::default();
        assert!(matches!(c.render(100), Err(MobileError::NoDocument)));
        assert!(matches!(c.info(), Err(MobileError::NoDocument)));
        assert!(matches!(c.open("x.png", &[]), Err(MobileError::InvalidInput { .. })));
        assert!(matches!(c.open("x.png", b"not an image"), Err(MobileError::Io { .. })));
        assert!(matches!(c.open("x.pcraft", b"garbage"), Err(MobileError::Io { .. })));
        assert!(matches!(c.new_document(0, 10), Err(MobileError::InvalidInput { .. })));
        assert!(matches!(c.new_document(100_000, 100_000), Err(MobileError::TooLarge { .. })));
        assert!(matches!(c.execute("file.new", r#"{"width": 99999, "height": 99999}"#), Err(MobileError::TooLarge { .. })));
        assert!(matches!(c.execute("layer.new.layer", "{not json"), Err(MobileError::InvalidInput { .. })));
        assert!(matches!(c.execute("no.such.command", ""), Err(MobileError::Command { .. })));
        assert!(matches!(c.export("", None), Err(MobileError::InvalidInput { .. }) | Err(MobileError::NoDocument)));
    }

    #[test]
    fn max_pixels_is_configurable() {
        let mut c = Core::default();
        c.set_max_pixels(100);
        assert!(matches!(c.new_document(11, 10), Err(MobileError::TooLarge { .. })));
        assert!(c.new_document(10, 10).is_ok());
        assert!(matches!(c.open("big.png", &png_bytes(20, 20)), Err(MobileError::TooLarge { .. })));
    }
}
