//! `LyricsUtils.parseLyrics` and the helpers it uses.

use crate::scan::{self, Stamp};
use crate::text;

#[derive(Debug, Clone, PartialEq)]
pub struct Word {
    pub text: String,
    /// Seconds.
    pub start: f64,
    /// Seconds.
    pub end: f64,
    pub trailing_space: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Entry {
    /// Milliseconds.
    pub time: i64,
    pub text: String,
    /// `None` means the line has no word timings. `Some(vec![])` is distinct and is kept.
    pub words: Option<Vec<Word>>,
    pub agent: Option<String>,
    pub is_background: bool,
}

/// Parses LRC, enhanced LRC and the Paxsenix `v1:` / `[bg: ...]` rich-sync formats.
/// The result is sorted by time with a stable sort, like Kotlin's `sorted()`.
pub fn parse_lyrics(lyrics: &str) -> Vec<Entry> {
    if text::is_blank(lyrics) {
        return Vec::new();
    }
    let decoded = text::decode_html_entities(&unescape(lyrics));
    let lines: Vec<&str> = text::lines(&decoded)
        .into_iter()
        .filter(|l| !text::is_blank(l))
        .filter(|l| !text::trim(l).starts_with("[offset:"))
        .collect();

    let is_rich = lines
        .iter()
        .any(|l| scan::match_rich_line(text::trim(l)).is_some() && !scan::find_word_matches(l).is_empty());

    let mut entries = if is_rich { parse_rich_sync(&lines) } else { parse_standard(&lines) };
    entries.sort_by_key(|e| e.time);
    entries
}

/// The "fast unescape" at the top of `parseLyrics`: only runs for text that holds a backslash or
/// starts with a quote, strips one surrounding quote on each side and handles `\\ \n \r \t`.
fn unescape(lyrics: &str) -> String {
    if !(lyrics.contains('\\') || lyrics.starts_with('"')) {
        return lyrics.to_string();
    }
    let t = text::trim(lyrics);
    let t = t.strip_prefix('"').unwrap_or(t);
    let t = t.strip_suffix('"').unwrap_or(t);
    let mut out = String::with_capacity(t.len());
    let mut chars = t.chars();
    while let Some(c) = chars.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match chars.next() {
            Some('\\') => out.push('\\'),
            Some('n') => out.push('\n'),
            Some('r') => out.push('\r'),
            Some('t') => out.push('\t'),
            Some(other) => {
                out.push('\\');
                out.push(other);
            }
            None => out.push('\\'),
        }
    }
    out
}

fn is_word_line(trimmed: &str) -> bool {
    trimmed.starts_with('<') && trimmed.ends_with('>')
}

/// `nextLine.trim()` wrapped in `<...>` read as `word:start:end|...` timings.
fn word_timings_from_next_line(index: usize, lines: &[&str]) -> Option<Vec<Word>> {
    let next = text::trim(lines.get(index + 1)?);
    if is_word_line(next) {
        parse_word_timestamps(&next[1..next.len() - 1])
    } else {
        None
    }
}

fn parse_rich_sync(lines: &[&str]) -> Vec<Entry> {
    let mut result = Vec::new();
    let mut last_non_bg_agent: Option<String> = None;

    for (index, line) in lines.iter().enumerate() {
        let trimmed = text::trim(line);

        if let Some(content) = scan::match_bg_line(trimmed) {
            let words = parse_rich_words(content, index, lines).or_else(|| word_timings_from_next_line(index, lines));
            let plain = text::trim(&scan::strip_angle_stamps(content)).to_string();
            let time = words.as_ref().and_then(|w| w.first()).map_or(0, |w| (w.start * 1000.0) as i64);
            result.push(Entry {
                time,
                text: plain,
                words,
                agent: Some(last_non_bg_agent.clone().unwrap_or_else(|| "bg".to_string())),
                is_background: true,
            });
            continue;
        }

        if let Some((stamp, agent, content)) = scan::find_agent_line(trimmed) {
            let words = parse_rich_words(content, index, lines).or_else(|| word_timings_from_next_line(index, lines));
            let plain = text::trim(&scan::strip_angle_stamps(content)).to_string();
            last_non_bg_agent = Some(agent.to_string());
            result.push(Entry {
                time: stamp.millis(),
                text: plain,
                words,
                agent: Some(agent.to_string()),
                is_background: false,
            });
            continue;
        }

        if let Some((stamp, rest)) = scan::match_rich_line(trimmed) {
            let mut content = text::trim_start(rest).to_string();

            let agent = scan::find_agent_marker(&content).map(|(a, s, e)| (a.to_string(), s, e));
            if let Some((_, start, end)) = &agent {
                content.replace_range(*start..*end, "");
            }
            let agent = agent.map(|(a, _, _)| a);

            let is_background = content.starts_with("{bg}");
            if is_background {
                content.replace_range(0..4, "");
            }

            let words = parse_rich_words(&content, index, lines).or_else(|| word_timings_from_next_line(index, lines));
            let plain = text::trim(&scan::strip_angle_stamps(&content)).to_string();

            if !is_background && agent.as_deref().is_some_and(|a| !text::is_blank(a)) {
                last_non_bg_agent = agent.clone();
            }
            let entry_agent = if is_background {
                Some(last_non_bg_agent.clone().unwrap_or_else(|| "bg".to_string()))
            } else {
                agent
            };
            result.push(Entry { time: stamp.millis(), text: plain, words, agent: entry_agent, is_background });
        }
    }
    result
}

/// `parseRichSyncWords`: word timings from `<stamp>word <stamp>word ...`, with the last word's
/// end taken from a trailing stamp, else the next line's start, else start + 0.5 s.
fn parse_rich_words(content: &str, current_index: usize, all_lines: &[&str]) -> Option<Vec<Word>> {
    let matches = scan::find_word_matches(content);
    let last = matches.last()?;

    let trailing = text::trim(&content[last.end..]);
    let trailing_match = scan::find_angle_stamp(trailing).or_else(|| scan::find_square_stamp(trailing));
    let trailing_end_time = trailing_match.and_then(|(stamp, _, end)| {
        let rest = &trailing[end..];
        let rest = rest.strip_suffix(']').unwrap_or(rest);
        text::is_blank(rest).then(|| stamp.seconds_f64())
    });

    let mut out = Vec::new();
    for (index, m) in matches.iter().enumerate() {
        let start = m.stamp.seconds_f64();
        let has_trailing_space = m.text.ends_with(' ');
        let words: Vec<&str> = text::trim(m.text).split(text::is_regex_space).filter(|w| !text::is_blank(w)).collect();

        let is_last_match = index == matches.len() - 1;
        let next_line_time = if is_last_match { next_line_start_time(current_index, all_lines) } else { None };
        let next = if is_last_match {
            trailing_end_time.or(next_line_time).unwrap_or(start + 0.5)
        } else {
            matches[index + 1].stamp.seconds_f64()
        };
        let count = words.len() as f64;

        for (wi, word) in words.iter().enumerate() {
            let last_in_group = wi == words.len() - 1;
            let last_overall = is_last_match && last_in_group;
            let w = wi as f64;

            let word_start = start + (next - start) * w / count;
            let word_end = if !last_in_group {
                start + (next - start) * (w + 1.0) / count
            } else if !last_overall {
                next
            } else {
                trailing_end_time.or(next_line_time).unwrap_or(start + 0.5)
            };
            let trailing_space = if !last_in_group {
                true
            } else if !last_overall {
                has_trailing_space
            } else {
                let after = match trailing_match {
                    Some((_, first, _)) => &trailing[..first],
                    None => trailing,
                };
                !text::is_blank(after)
            };
            out.push(Word { text: (*word).to_string(), start: word_start, end: word_end, trailing_space });
        }
    }
    (!out.is_empty()).then_some(out)
}

/// `getNextLineStartTime`: start of the next line, from a rich line or a `[bg: ...]` line.
fn next_line_start_time(current_index: usize, all_lines: &[&str]) -> Option<f64> {
    let next = text::trim(all_lines.get(current_index + 1)?);
    if let Some((stamp, _)) = scan::match_rich_line(next) {
        return Some(stamp.seconds_f64());
    }
    let content = scan::match_bg_line(next)?;
    scan::find_word_matches_first(content).map(|m| m.stamp.seconds_f64())
}

fn parse_standard(lines: &[&str]) -> Vec<Entry> {
    let mut result = Vec::new();
    for (i, line) in lines.iter().enumerate() {
        let trimmed = text::trim(line);
        if is_word_line(trimmed) {
            continue;
        }
        let Some(entries) = parse_line(line) else { continue };
        let words = word_timings_from_next_line(i, lines);
        result.extend(entries.into_iter().map(|mut e| {
            if words.is_some() {
                e.words = words.clone();
            }
            e
        }));
    }
    result
}

/// `parseWordTimestamps`: `text:start:end|text:start:end`. `None` for blank input. An input where
/// no part has three or more `:` separated fields gives `Some(vec![])`, not `None`.
fn parse_word_timestamps(data: &str) -> Option<Vec<Word>> {
    if text::is_blank(data) {
        return None;
    }
    let parts: Vec<&str> = data.split('|').collect();
    let last = *parts.last()?;
    let words = parts
        .iter()
        .filter_map(|word_data| {
            let fields: Vec<&str> = word_data.split(':').collect();
            if fields.len() < 3 {
                return None;
            }
            let n = fields.len();
            Some(Word {
                text: fields[..n - 2].join(":"),
                start: text::to_double_or_null(fields[n - 2]).unwrap_or(0.0),
                end: text::to_double_or_null(fields[n - 1]).unwrap_or(0.0),
                // Kotlin compares by value, so a repeated final part is also "last".
                trailing_space: *word_data != last,
            })
        })
        .collect();
    Some(words)
}

/// `parseLine`: one entry per leading `[mm:ss.xx]` stamp.
fn parse_line(line: &str) -> Option<Vec<Entry>> {
    let (stamps, rest) = scan::match_line(text::trim(line))?;
    let mut body = rest.to_string();

    let agent = scan::find_agent_marker(&body).map(|(a, s, e)| (a.to_string(), s, e));
    if let Some((_, start, end)) = &agent {
        body.replace_range(*start..*end, "");
    }
    let agent = agent.map(|(a, _, _)| a);

    let is_background = body.starts_with("{bg}");
    if is_background {
        body.replace_range(0..4, "");
    }

    Some(
        stamps
            .into_iter()
            .map(|s: Stamp| Entry {
                time: s.millis(),
                text: body.clone(),
                words: None,
                agent: agent.clone(),
                is_background,
            })
            .collect(),
    )
}
