//! Wavlin lyrics core, ported from `integration/lyrics/LyricsUtils.kt`.
//!
//! - [`parse::parse_lyrics`] is `LyricsUtils.parseLyrics`
//! - [`credit::filter_credit_lines`] is `LyricsUtils.filterLyricsCreditLines`
//!
//! The Kotlin patterns are Java regexes. This crate has no dependencies and implements each of
//! them as a small scanner in [`scan`]; Kotlin-specific string behaviour (whitespace set, `lines()`,
//! `toIntOrNull`, `toDoubleOrNull`) lives in [`text`]. Known deviations from Kotlin are listed
//! on the functions concerned.
//!
//! Not ported: line lookup during playback (`findCurrentLineIndex`, `findActiveLineIndices`),
//! romanization and language detection. They need no parsing, run on every frame, and would cost
//! a JNI call each time.

pub mod credit;
pub mod pack;
pub mod parse;
pub mod scan;
pub mod text;

pub use credit::filter_credit_lines;
pub use pack::{pack, Packed};
pub use parse::{parse_lyrics, Entry, Word};
