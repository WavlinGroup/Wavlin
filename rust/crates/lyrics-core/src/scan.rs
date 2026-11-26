//! Hand-written equivalents of the regexes in `LyricsUtils.kt`.
//!
//! Each matcher works on bytes: every structural character in these patterns is ASCII, so all
//! returned offsets are valid `str` boundaries. Digit runs are checked for their exact length
//! because the Kotlin patterns cannot backtrack into a longer run (`\d{2,3}` followed by a fixed
//! closer fails on "1234]").

use crate::text::{is_line_terminator, is_regex_space};

/// A parsed `mm:ss.ff` or `mm:ss.fff` body.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Stamp {
    pub minutes: i64,
    pub seconds: i64,
    pub fraction: i64,
    /// 2 means centiseconds, 3 means milliseconds.
    pub fraction_len: usize,
}

impl Stamp {
    /// Milliseconds as the Kotlin line parsers compute them.
    pub fn millis(self) -> i64 {
        let frac = if self.fraction_len == 3 { self.fraction } else { self.fraction * 10 };
        self.minutes * 60_000 + self.seconds * 1_000 + frac
    }

    /// Seconds as the Kotlin word parsers compute them. The operation order is kept so the doubles
    /// are bit-identical: `minutes * 60.0 + seconds + fractionPart`.
    pub fn seconds_f64(self) -> f64 {
        let frac = if self.fraction_len == 3 { self.fraction as f64 / 1000.0 } else { self.fraction as f64 / 100.0 };
        self.minutes as f64 * 60.0 + self.seconds as f64 + frac
    }
}

fn digit_run(b: &[u8], pos: usize) -> usize {
    b.get(pos..).map_or(0, |rest| rest.iter().take_while(|c| c.is_ascii_digit()).count())
}

fn number(b: &[u8], pos: usize, len: usize) -> i64 {
    b[pos..pos + len].iter().fold(0i64, |acc, c| acc * 10 + i64::from(c - b'0'))
}

/// `(\d{1,2}|\d\d):(\d\d)\.(\d{2,3})` starting at `pos`. Returns the stamp and the end offset.
pub fn stamp_body(b: &[u8], pos: usize, flexible_minutes: bool) -> Option<(Stamp, usize)> {
    let m = digit_run(b, pos);
    let minutes_ok = if flexible_minutes { (1..=2).contains(&m) } else { m == 2 };
    if !minutes_ok || b.get(pos + m) != Some(&b':') {
        return None;
    }
    let s_pos = pos + m + 1;
    if digit_run(b, s_pos) != 2 || b.get(s_pos + 2) != Some(&b'.') {
        return None;
    }
    let f_pos = s_pos + 3;
    let f = digit_run(b, f_pos);
    if !(2..=3).contains(&f) {
        return None;
    }
    let stamp = Stamp {
        minutes: number(b, pos, m),
        seconds: number(b, s_pos, 2),
        fraction: number(b, f_pos, f),
        fraction_len: f,
    };
    Some((stamp, f_pos + f))
}

/// `\[stamp\]` at `pos`. Returns the stamp and the offset after `]`.
pub fn bracket_stamp(b: &[u8], pos: usize, flexible_minutes: bool) -> Option<(Stamp, usize)> {
    if b.get(pos) != Some(&b'[') {
        return None;
    }
    let (stamp, end) = stamp_body(b, pos + 1, flexible_minutes)?;
    (b.get(end) == Some(&b']')).then_some((stamp, end + 1))
}

/// `<stamp>` at `pos`. Returns the stamp and the offset after `>`.
pub fn angle_stamp(b: &[u8], pos: usize) -> Option<(Stamp, usize)> {
    if b.get(pos) != Some(&b'<') {
        return None;
    }
    let (stamp, end) = stamp_body(b, pos + 1, true)?;
    (b.get(end) == Some(&b'>')).then_some((stamp, end + 1))
}

/// One `RICH_SYNC_WORD_REGEX` match: `<stamp>([^<]+)`.
#[derive(Debug, Clone, Copy)]
pub struct WordMatch<'a> {
    pub stamp: Stamp,
    pub text: &'a str,
    /// Exclusive end offset of the whole match.
    pub end: usize,
}

/// `RICH_SYNC_WORD_REGEX.findAll(s)`.
pub fn find_word_matches(s: &str) -> Vec<WordMatch<'_>> {
    let b = s.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'<' {
            if let Some((stamp, after)) = angle_stamp(b, i) {
                let text_end = b[after..].iter().position(|&c| c == b'<').map_or(b.len(), |p| after + p);
                if text_end > after {
                    out.push(WordMatch { stamp, text: &s[after..text_end], end: text_end });
                    i = text_end;
                    continue;
                }
            }
        }
        i += 1;
    }
    out
}

/// `RICH_SYNC_WORD_REGEX.find(s)`: the first word match, if any.
pub fn find_word_matches_first(s: &str) -> Option<WordMatch<'_>> {
    find_word_matches(s).into_iter().next()
}

/// First `<stamp>` anywhere in `s`: the stamp and its `[start, end)` offsets.
pub fn find_angle_stamp(s: &str) -> Option<(Stamp, usize, usize)> {
    let b = s.as_bytes();
    (0..b.len()).find_map(|i| angle_stamp(b, i).map(|(st, end)| (st, i, end)))
}

/// First `[stamp]` with one or two minute digits anywhere in `s`.
pub fn find_square_stamp(s: &str) -> Option<(Stamp, usize, usize)> {
    let b = s.as_bytes();
    (0..b.len()).find_map(|i| bracket_stamp(b, i, true).map(|(st, end)| (st, i, end)))
}

/// `RICH_SYNC_LINE_REGEX.matchEntire(line)`: `\[(\d{1,2}):(\d{2})\.(\d{2,3})\](.*)`.
/// Returns the stamp and the content after `]`. `None` when the content holds a line terminator.
pub fn match_rich_line(line: &str) -> Option<(Stamp, &str)> {
    let (stamp, end) = bracket_stamp(line.as_bytes(), 0, true)?;
    let content = &line[end..];
    (!content.chars().any(is_line_terminator)).then_some((stamp, content))
}

/// `PAXSENIX_BG_LINE_REGEX`: `^\[bg:\s*(.*)\]$`. Returns the content between `bg:` and the final `]`.
/// `$` also matches before one final U+0085, which is the only terminator a trimmed line can end with.
pub fn match_bg_line(line: &str) -> Option<&str> {
    let rest = line.strip_prefix("[bg:")?;
    let rest = rest.trim_start_matches(is_regex_space);
    let rest = rest.strip_suffix('\u{85}').unwrap_or(rest);
    let content = rest.strip_suffix(']')?;
    (!content.chars().any(is_line_terminator)).then_some(content)
}

/// `PAXSENIX_AGENT_LINE_REGEX.find(line)`: `\[(\d{1,2}):(\d{2})\.(\d{2,3})\](v\d+):\s*(.*)`.
/// Returns the stamp, the agent (`v1`) and the content up to the first line terminator.
pub fn find_agent_line(line: &str) -> Option<(Stamp, &str, &str)> {
    let b = line.as_bytes();
    for i in 0..b.len() {
        let Some((stamp, after)) = bracket_stamp(b, i, true) else { continue };
        if b.get(after) != Some(&b'v') {
            continue;
        }
        let digits = digit_run(b, after + 1);
        if digits == 0 || b.get(after + 1 + digits) != Some(&b':') {
            continue;
        }
        let agent = &line[after..after + 1 + digits];
        let rest = line[after + 2 + digits..].trim_start_matches(is_regex_space);
        let content = rest.find(is_line_terminator).map_or(rest, |p| &rest[..p]);
        return Some((stamp, agent, content));
    }
    None
}

/// `LINE_REGEX.matchEntire(line)`: `((\[\d\d:\d\d\.\d{2,3}\] ?)+)(.*)`.
/// Returns every leading stamp and the text after the last one (and its optional space).
pub fn match_line(line: &str) -> Option<(Vec<Stamp>, &str)> {
    let b = line.as_bytes();
    let mut stamps = Vec::new();
    let mut pos = 0;
    while let Some((stamp, end)) = bracket_stamp(b, pos, false) {
        stamps.push(stamp);
        pos = if b.get(end) == Some(&b' ') { end + 1 } else { end };
    }
    if stamps.is_empty() {
        return None;
    }
    let text = &line[pos..];
    (!text.chars().any(is_line_terminator)).then_some((stamps, text))
}

/// `AGENT_REGEX.find(text)`: `\{agent:([^}]+)\}`. Returns the agent and the `[start, end)` of the match.
pub fn find_agent_marker(text: &str) -> Option<(&str, usize, usize)> {
    const OPEN: &str = "{agent:";
    let mut from = 0;
    while let Some(rel) = text[from..].find(OPEN) {
        let start = from + rel;
        let body = start + OPEN.len();
        if let Some(close) = text[body..].find('}') {
            if close > 0 {
                return Some((&text[body..body + close], start, body + close + 1));
            }
        }
        from = start + 1;
    }
    None
}

/// Removes every `<stamp>\s*` from `content`, as `content.replace(Regex("<\d{1,2}:\d{2}\.\d{2,3}>\s*"), "")`.
pub fn strip_angle_stamps(content: &str) -> String {
    let b = content.as_bytes();
    let mut out = String::with_capacity(content.len());
    let (mut copy_from, mut i) = (0, 0);
    while i < b.len() {
        if let Some((_, end)) = angle_stamp(b, i) {
            out.push_str(&content[copy_from..i]);
            let skipped = content[end..].len() - content[end..].trim_start_matches(is_regex_space).len();
            i = end + skipped;
            copy_from = i;
        } else {
            i += 1;
        }
    }
    out.push_str(&content[copy_from..]);
    out
}
