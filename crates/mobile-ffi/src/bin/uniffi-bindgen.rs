//! Host tool: generates the Kotlin bindings from the compiled library.
//! `cargo run -p photocraft-mobile-ffi --features bindgen --bin uniffi-bindgen -- generate --library <lib> --language kotlin --out-dir <dir>`
fn main() {
    uniffi::uniffi_bindgen_main()
}
