//! Expected values are traced by hand from `LyricsUtils.kt`; the Kotlin code was not run against
//! these inputs, so they pin the intended behaviour of the port, not a recorded Kotlin output.

use lyrics_core::text::{decode_html_entities, is_blank, lines, to_double_or_null, to_int_or_null};
use lyrics_core::{filter_credit_lines, pack, parse_lyrics, Word};

fn w(text: &str, start: f64, end: f64, trailing_space: bool) -> Word {
    Word { text: text.to_string(), start, end, trailing_space }
}

#[test]
fn blank_input_gives_no_entries() {
    assert!(parse_lyrics("").is_empty());
    assert!(parse_lyrics(" \n\u{A0}\t").is_empty());
}

#[test]
fn standard_lines_use_centiseconds_and_milliseconds() {
    let e = parse_lyrics("[00:12.34] hello\n[00:15.500]world");
    assert_eq!(e.len(), 2);
    assert_eq!((e[0].time, e[0].text.as_str()), (12_340, "hello"));
    assert_eq!((e[1].time, e[1].text.as_str()), (15_500, "world"));
    assert!(e.iter().all(|x| x.words.is_none() && x.agent.is_none() && !x.is_background));
}

#[test]
fn several_stamps_on_one_line_are_sorted() {
    let e = parse_lyrics("[00:01.00][00:03.00] hi\n[00:02.00] mid");
    let got: Vec<_> = e.iter().map(|x| (x.time, x.text.as_str())).collect();
    assert_eq!(got, [(1000, "hi"), (2000, "mid"), (3000, "hi")]);
}

#[test]
fn offset_and_metadata_tags_are_ignored() {
    let e = parse_lyrics("[offset:500]\n[ti:Song]\n[00:01.00] a");
    assert_eq!(e.len(), 1);
    assert_eq!(e[0].text, "a");
}

#[test]
fn text_after_a_line_separator_is_not_a_line() {
    // Java's `.` does not match U+2028, so LINE_REGEX.matchEntire fails for the whole line.
    assert!(parse_lyrics("[00:01.00] a\u{2028}b").is_empty());
}

#[test]
fn agent_and_background_markers_in_standard_lines() {
    let e = parse_lyrics("[00:01.00]{agent:v2}{bg}ya");
    assert_eq!(e[0].text, "ya");
    assert_eq!(e[0].agent.as_deref(), Some("v2"));
    assert!(e[0].is_background);
}

#[test]
fn legacy_word_line_after_a_standard_line() {
    let e = parse_lyrics("[00:01.00] hi there\n<hi:1.0:1.5|there:1.5:2.0>");
    assert_eq!(e.len(), 1);
    assert_eq!(e[0].words, Some(vec![w("hi", 1.0, 1.5, true), w("there", 1.5, 2.0, false)]));
}

#[test]
fn unparseable_word_line_gives_some_empty_not_none() {
    let e = parse_lyrics("[00:01.00] x\n<abc>");
    assert_eq!(e[0].words, Some(vec![]));
}

#[test]
fn rich_sync_with_trailing_end_stamp() {
    let e = parse_lyrics("[00:01.000]<00:01.000>Hello <00:01.500>world<00:02.000>\n[00:03.000]<00:03.000>Next");
    assert_eq!(e.len(), 2);
    assert_eq!((e[0].time, e[0].text.as_str()), (1000, "Hello world"));
    assert_eq!(e[0].words, Some(vec![w("Hello", 1.0, 1.5, true), w("world", 1.5, 2.0, false)]));
    assert_eq!((e[1].time, e[1].text.as_str()), (3000, "Next"));
    assert_eq!(e[1].words, Some(vec![w("Next", 3.0, 3.5, false)]));
}

#[test]
fn last_word_ends_at_the_next_line_when_there_is_no_trailing_stamp() {
    let e = parse_lyrics("[00:01.000]<00:01.000>a <00:01.500>b\n[00:03.000]<00:03.000>c");
    assert_eq!(e[0].words, Some(vec![w("a", 1.0, 1.5, true), w("b", 1.5, 3.0, false)]));
}

#[test]
fn several_words_inside_one_stamp_split_the_span_evenly() {
    let e = parse_lyrics("[00:00.000]<00:00.000>one two<00:02.000>x");
    assert_eq!(
        e[0].words,
        Some(vec![w("one", 0.0, 1.0, true), w("two", 1.0, 2.0, false), w("x", 2.0, 2.5, false)])
    );
}

#[test]
fn paxsenix_agent_and_background_lines() {
    let e = parse_lyrics("[00:00.000]v1: <00:00.000>I <00:00.154>promise<00:01.000>\n[bg: <00:00.500>Yeah<00:00.900>]");
    assert_eq!(e.len(), 2);
    assert_eq!((e[0].time, e[0].text.as_str(), e[0].agent.as_deref(), e[0].is_background), (0, "I promise", Some("v1"), false));
    assert_eq!(e[0].words, Some(vec![w("I", 0.0, 0.154, true), w("promise", 0.154, 1.0, false)]));
    assert_eq!((e[1].time, e[1].text.as_str(), e[1].agent.as_deref(), e[1].is_background), (500, "Yeah", Some("v1"), true));
    assert_eq!(e[1].words, Some(vec![w("Yeah", 0.5, 0.9, false)]));
}

#[test]
fn rich_sync_agent_marker_and_background_marker() {
    let e = parse_lyrics("[00:01.000]{agent:v1}<00:01.000>Hi\n[00:02.000]{bg}<00:02.000>ooh");
    assert_eq!((e[0].agent.as_deref(), e[0].is_background, e[0].text.as_str()), (Some("v1"), false, "Hi"));
    assert_eq!((e[1].agent.as_deref(), e[1].is_background, e[1].text.as_str()), (Some("v1"), true, "ooh"));
}

#[test]
fn quoted_and_escaped_input_is_unescaped_first() {
    let e = parse_lyrics("\"[00:01.00] a\\n[00:02.00] b\"");
    let got: Vec<_> = e.iter().map(|x| (x.time, x.text.as_str())).collect();
    assert_eq!(got, [(1000, "a"), (2000, "b")]);
}

#[test]
fn html_entities_decode_like_kotlin() {
    let e = parse_lyrics("[00:01.00] Don&apos;t &amp; &#65;&#x42; &bogus; &#xZZ; &#-5; &#1114112;");
    assert_eq!(e[0].text, "Don't & AB &bogus; &#xZZ; \u{FFFD} \u{FFFD}");
    assert_eq!(decode_html_entities("a&nbsp;b"), "a b");
    assert_eq!(decode_html_entities("&#x1F600;"), "\u{1F600}");
    // More than 11 units before the ';' is left alone.
    assert_eq!(decode_html_entities("&#123456789012;"), "&#123456789012;");
}

#[test]
fn credit_lines_are_removed_and_the_rest_is_kept_verbatim() {
    let input = "[00:00.00] Synced by foo\n[00:01.00] real\nLyrics by x\n{agent:v1}Music by y\nv2: arranged by z\n[Synced by Bob]\n[bg: music by q]";
    assert_eq!(filter_credit_lines(input), "[00:01.00] real\n[bg: music by q]");
}

#[test]
fn kotlin_string_helpers() {
    assert_eq!(lines("a\r\nb\rc\nd"), ["a", "b", "c", "d"]);
    assert_eq!(lines("a\n"), ["a", ""]);
    assert!(is_blank("\u{A0}\u{2003}\u{1C}"));
    assert!(!is_blank("\u{85}"));
    assert_eq!(to_int_or_null("+65", 10), Some(65));
    assert_eq!(to_int_or_null("-", 10), None);
    assert_eq!(to_int_or_null("2147483648", 10), None);
    assert_eq!(to_int_or_null("-2147483648", 10), Some(i32::MIN));
    assert_eq!(to_double_or_null("1.5"), Some(1.5));
    assert_eq!(to_double_or_null(" 2 "), Some(2.0));
    assert_eq!(to_double_or_null("1.5f"), Some(1.5));
    assert_eq!(to_double_or_null("1e3"), Some(1000.0));
    assert!(to_double_or_null("NaN").unwrap().is_nan());
    assert_eq!(to_double_or_null("inf"), None);
    assert_eq!(to_double_or_null("abc"), None);
    assert_eq!(to_double_or_null("."), None);
    assert_eq!(to_double_or_null(""), None);
}

#[test]
fn packing_keeps_none_and_empty_words_apart() {
    let e = parse_lyrics("[00:01.00] a\n<x:1:2>\n[00:02.00] b\n<abc>\n[00:03.00] c");
    let p = pack(&e).unwrap();
    assert_eq!(p.times, [1000, 2000, 3000]);
    assert_eq!(p.word_counts, [1, 0, -1]);
    assert_eq!(p.word_texts, ["x"]);
    assert_eq!((p.word_starts[0], p.word_ends[0], p.word_trailing_spaces[0]), (1.0, 2.0, false));
}

#[test]
fn bounded_work_on_hostile_input() {
    let long = "<".repeat(200_000) + &"[00:00.00]".repeat(1000);
    let _ = parse_lyrics(&long);
    let _ = filter_credit_lines(&long);
}
