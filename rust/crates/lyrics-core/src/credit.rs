//! `LyricsUtils.filterLyricsCreditLines`.

use crate::scan;
use crate::text;

/// Drops lines whose text, after removing leading stamps and agent / background markers, starts
/// with a credit prefix. Kept lines are returned unchanged and joined with `\n`.
///
/// Lowercasing is locale independent. Kotlin uses `Locale.getDefault()`, which only differs for
/// languages such as Turkish where `I` lowercases to a dotless `ı`.
pub fn filter_credit_lines(lyrics: &str) -> String {
    text::lines(lyrics).into_iter().filter(|line| !is_credit_line(line)).collect::<Vec<_>>().join("\n")
}

fn is_credit_line(line: &str) -> bool {
    let mut content = text::trim(line).to_string();
    loop {
        let before = content.len();
        content = strip_prefixes(&content);
        if content.len() >= before {
            break;
        }
    }
    let lower = content.to_lowercase();
    lower.starts_with("synced by")
        || lower.starts_with("lyrics by")
        || lower.starts_with("music by")
        || lower.starts_with("arranged by")
        || (lower.starts_with('[')
            && lower.ends_with(']')
            && lower.encode_utf16().count() < 40
            && lower.contains("synced by"))
}

/// One pass of the Kotlin loop body: the five `replaceFirst("^...")` calls in order, then `trim()`.
fn strip_prefixes(s: &str) -> String {
    let mut t = s;
    if let Some((_, end)) = scan::bracket_stamp(t.as_bytes(), 0, false) {
        t = &t[end..];
    }
    if let Some(rest) = strip_agent_marker(t) {
        t = rest;
    }
    if let Some(rest) = t.strip_prefix("{bg}") {
        t = rest;
    }
    if let Some(rest) = strip_bg_bracket(t) {
        t = rest;
    }
    if let Some(rest) = strip_version_prefix(t) {
        t = rest;
    }
    text::trim(t).to_string()
}

/// `^\{agent:[^}]+\}`
fn strip_agent_marker(s: &str) -> Option<&str> {
    let body = s.strip_prefix("{agent:")?;
    let close = body.find('}')?;
    (close > 0).then(|| &body[close + 1..])
}

/// `^\[bg:.*\]`: greedy up to the last `]` before the first line terminator.
fn strip_bg_bracket(s: &str) -> Option<&str> {
    let body = s.strip_prefix("[bg:")?;
    let limit = body.find(text::is_line_terminator).unwrap_or(body.len());
    let close = body[..limit].rfind(']')?;
    Some(&body[close + 1..])
}

/// `^v\d+:`
fn strip_version_prefix(s: &str) -> Option<&str> {
    let body = s.strip_prefix('v')?;
    let digits = body.bytes().take_while(u8::is_ascii_digit).count();
    (digits > 0 && body.as_bytes().get(digits) == Some(&b':')).then(|| &body[digits + 1..])
}
