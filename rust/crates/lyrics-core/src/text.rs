//! Kotlin/Java string semantics that differ from Rust's, kept in one place so the parser can be
//! checked against the Kotlin source line by line.

/// `Char.isWhitespace()` on Kotlin/JVM: `Character.isWhitespace(c) || Character.isSpaceChar(c)`.
///
/// That is the Unicode space separators (Zs, Zl, Zp) plus `\t \n \u000B \f \r` and `\u001C..\u001F`.
/// It differs from Rust's `char::is_whitespace`, which includes U+0085 and excludes `\u001C..\u001F`.
/// U+180E is treated as not whitespace (Unicode 6.3 and later).
pub fn is_whitespace(c: char) -> bool {
    matches!(
        c,
        '\t' | '\n'
            | '\u{0B}'
            | '\u{0C}'
            | '\r'
            | '\u{1C}'..='\u{1F}'
            | ' '
            | '\u{A0}'
            | '\u{1680}'
            | '\u{2000}'..='\u{200A}'
            | '\u{2028}'
            | '\u{2029}'
            | '\u{202F}'
            | '\u{205F}'
            | '\u{3000}'
    )
}

/// Java regex `\s` without UNICODE_CHARACTER_CLASS: `[ \t\n\x0B\f\r]`.
pub fn is_regex_space(c: char) -> bool {
    matches!(c, ' ' | '\t' | '\n' | '\u{0B}' | '\u{0C}' | '\r')
}

/// Characters the Java regex `.` does not match by default.
pub fn is_line_terminator(c: char) -> bool {
    matches!(c, '\n' | '\r' | '\u{85}' | '\u{2028}' | '\u{2029}')
}

pub fn trim(s: &str) -> &str {
    s.trim_matches(is_whitespace)
}

pub fn trim_start(s: &str) -> &str {
    s.trim_start_matches(is_whitespace)
}

pub fn is_blank(s: &str) -> bool {
    s.chars().all(is_whitespace)
}

/// `String.lines()` in Kotlin: splits on `\r\n`, `\n` and `\r`, keeping a final empty line.
pub fn lines(s: &str) -> Vec<&str> {
    let b = s.as_bytes();
    let mut out = Vec::new();
    let (mut start, mut i) = (0, 0);
    while i < b.len() {
        match b[i] {
            b'\n' => {
                out.push(&s[start..i]);
                i += 1;
                start = i;
            }
            b'\r' => {
                out.push(&s[start..i]);
                i += if b.get(i + 1) == Some(&b'\n') { 2 } else { 1 };
                start = i;
            }
            _ => i += 1,
        }
    }
    out.push(&s[start..]);
    out
}

/// `String.toIntOrNull(radix)`: optional `+` or `-`, ASCII digits only, rejects overflow.
///
/// Kotlin also accepts non-ASCII Unicode digits here; those are rejected in this port.
pub fn to_int_or_null(s: &str, radix: u32) -> Option<i32> {
    let b = s.as_bytes();
    let first = *b.first()?;
    let (negative, start) = match first {
        b'-' => (true, 1),
        b'+' => (false, 1),
        _ => (false, 0),
    };
    if start == b.len() {
        return None;
    }
    let mut acc: i64 = 0;
    for &c in &b[start..] {
        let digit = (c as char).to_digit(radix)?;
        acc = acc * i64::from(radix) + i64::from(digit);
        if acc > i64::from(i32::MAX) + 1 {
            return None;
        }
    }
    i32::try_from(if negative { -acc } else { acc }).ok()
}

/// `String.toDoubleOrNull()`: Java's `Double.parseDouble` grammar.
///
/// Accepts surrounding whitespace up to U+0020, a type suffix (`f`, `F`, `d`, `D`), `NaN` and
/// `Infinity` spelled exactly. Hexadecimal floating point literals are not supported and give `None`.
pub fn to_double_or_null(s: &str) -> Option<f64> {
    let t = s.trim_matches(|c: char| c <= ' ');
    match t {
        "NaN" | "+NaN" | "-NaN" => return Some(f64::NAN),
        "Infinity" | "+Infinity" => return Some(f64::INFINITY),
        "-Infinity" => return Some(f64::NEG_INFINITY),
        _ => {}
    }
    let body = t.strip_suffix(['f', 'F', 'd', 'D']).unwrap_or(t);
    if body.is_empty() || !body.bytes().all(|c| matches!(c, b'0'..=b'9' | b'+' | b'-' | b'.' | b'e' | b'E')) {
        return None;
    }
    body.parse::<f64>().ok()
}

/// `String.decodeHtmlEntities` from `LyricsUtils`: `&apos; &quot; &lt; &gt; &nbsp; &amp;` plus
/// decimal and hexadecimal numeric references no longer than 11 UTF-16 units before the `;`.
///
/// Code points in the surrogate range cannot live in a Rust `String` and become U+FFFD, where
/// Kotlin would insert the lone surrogate.
pub fn decode_html_entities(text: &str) -> String {
    if !text.contains('&') {
        return text.to_string();
    }
    let chars: Vec<char> = text.chars().collect();
    let mut out = String::with_capacity(text.len());
    let mut i = 0;
    while i < chars.len() {
        if chars[i] == '&' {
            if let Some(rel) = chars[i + 1..].iter().position(|&c| c == ';') {
                let end = i + 1 + rel;
                let units: usize = chars[i..end].iter().map(|c| c.len_utf16()).sum();
                if units < 12 {
                    let entity: String = chars[i..=end].iter().collect();
                    if let Some(decoded) = decode_entity(&entity) {
                        out.push_str(&decoded);
                        i = end + 1;
                        continue;
                    }
                }
            }
        }
        out.push(chars[i]);
        i += 1;
    }
    out
}

fn decode_entity(entity: &str) -> Option<String> {
    match entity {
        "&apos;" => Some("'".into()),
        "&quot;" => Some("\"".into()),
        "&lt;" => Some("<".into()),
        "&gt;" => Some(">".into()),
        "&nbsp;" => Some(" ".into()),
        "&amp;" => Some("&".into()),
        _ if entity.starts_with("&#x") => to_int_or_null(&entity[3..entity.len() - 1], 16).map(code_point),
        _ if entity.starts_with("&#") => to_int_or_null(&entity[2..entity.len() - 1], 10).map(code_point),
        _ => None,
    }
}

fn code_point(value: i32) -> String {
    u32::try_from(value)
        .ok()
        .and_then(char::from_u32)
        .unwrap_or('\u{FFFD}')
        .to_string()
}
