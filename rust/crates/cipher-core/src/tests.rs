//! Synthetic snippets shaped like the patterns' own comments in `FunctionNameExtractor.kt`.
//! None of this is real player.js, so these tests prove the port, not that a live player matches.

use super::*;

#[test]
fn q_array_detected_and_absent() {
    assert!(has_q_array(r#"x;var Q = "a}b}c" . split ( "}" ) ;y"#));
    assert!(!has_q_array(r#"var Q="abc".split(",")"#));
    assert!(!has_q_array(""));
}

#[test]
fn player_hash_url_patterns_in_order() {
    assert_eq!(find_player_hash(r#"{"jsUrl":"/s/player/0a1b2c3d/player_ias.vflset/en_US/base.js"}"#).as_deref(), Some("0a1b2c3d"));
    assert_eq!(find_player_hash("player_ias.vflset/en_US/deadbeef/base.js").as_deref(), Some("deadbeef"));
    assert_eq!(find_player_hash("https://x/s/player/12345678/x").as_deref(), Some("12345678"));
    // jsUrl pattern wins over a later bare /s/player/ match.
    assert_eq!(find_player_hash(r#"/s/player/11111111/ jsUrl":"/player/22222222/""#).as_deref(), Some("22222222"));
    assert_eq!(find_player_hash("/s/player/NOTHEX00/"), None);
    assert_eq!(find_player_hash("/s/player/abc/"), None);
}

#[test]
fn signature_timestamp_anchored_and_loose() {
    assert_eq!(find_anchored_sts(r#"{"signatureTimestamp":20450,"x":1}"#), Some(20450));
    assert_eq!(find_anchored_sts("signatureTimestamp: 7"), Some(7));
    assert_eq!(find_anchored_sts("signatureTimestamp=7"), None);
    assert_eq!(find_loose_sts(r#"{"sts":19999}"#), Some(19999));
    assert_eq!(find_loose_sts("sts=5"), None);
    // Overflowing digits behave like Kotlin's toIntOrNull.
    assert_eq!(find_anchored_sts("signatureTimestamp:99999999999"), None);
}

#[test]
fn sig_modern_patterns_capture_name_and_constant() {
    let m = find_sig_function("a&&(b=Xy$(48,decodeURIComponent(c))").unwrap();
    assert_eq!((m.name.as_str(), m.constant_arg, m.pattern_index), ("Xy$", Some(48), 0));
    let m = find_sig_function("&& ( z = hJ ( 6 , decodeURIComponent ( h.s ) )").unwrap();
    assert_eq!((m.name.as_str(), m.constant_arg, m.pattern_index), ("hJ", Some(6), 1));
}

#[test]
fn sig_classic_patterns_have_no_constant() {
    let m = find_sig_function("c&&d.set(b,encodeURIComponent(sigFn(").unwrap();
    assert_eq!((m.name.as_str(), m.constant_arg, m.pattern_index), ("sigFn", None, 2));
    let m = find_sig_function("var m=abc(decodeURIComponent(h.s))").unwrap();
    assert_eq!((m.name.as_str(), m.constant_arg, m.pattern_index), ("abc", None, 4));
    assert!(find_sig_function("m=a(decodeURIComponent(h.s))").is_none()); // name needs 2+ chars
    assert!(find_sig_function("nothing here").is_none());
}

#[test]
fn sig_constant_overflow_is_none_but_name_kept() {
    let m = find_sig_function("&&(b=Fn(99999999999,decodeURIComponent(c))").unwrap();
    assert_eq!((m.name.as_str(), m.constant_arg), ("Fn", None));
}

#[test]
fn n_pattern_zero_with_and_without_index() {
    let m = find_n_function(r#"a.get("n"))&&(b=Fx[0](c)"#).unwrap();
    assert_eq!((m.name.as_str(), m.array_index, m.pattern_index), ("Fx", Some(0), 0));
    let m = find_n_function(r#"a.get("n"))&&(b=Fx(c)"#).unwrap();
    assert_eq!((m.name.as_str(), m.array_index), ("Fx", None));
}

#[test]
fn n_pattern_one_requires_matching_backreference() {
    let m = find_n_function(r#"a.get("n")) && (f = Arr[3](f)"#).unwrap();
    assert_eq!((m.name.as_str(), m.array_index, m.pattern_index), ("Arr", Some(3), 1));
    // Argument differs from the assigned variable: Kotlin's \1 would reject it.
    assert!(find_n_function(r#"a.get("n"))&&(f=Arr[3](g)"#).is_none());
    // A rejected first candidate must not hide a later valid one.
    let two = r#"a.get("n"))&&(f=Bad[1](g) ... a.get("n"))&&(h=Good[2](h)"#;
    let m = find_n_function(two).unwrap();
    assert_eq!((m.name.as_str(), m.array_index), ("Good", Some(2)));
}

#[test]
fn n_pattern_two_is_skipped_and_later_ones_still_match() {
    let js = r#"x.get("n");if(m){var M=n.match(1)} (k=String.fromCharCode(110)"#;
    let m = find_n_function(js).unwrap();
    assert_eq!((m.name.as_str(), m.pattern_index), ("k", 3));
    assert!(find_n_function(r#"x.get("n");if(m){var M=n.match(1)}"#).is_none());
}

#[test]
fn n_pattern_four_enhanced_except() {
    let m = find_n_function(r#"zq = function(a) { var b=1; return "enhanced_except_x" }"#).unwrap();
    assert_eq!((m.name.as_str(), m.array_index, m.pattern_index), ("zq", None, 4));
}

#[test]
fn large_input_is_linear_enough() {
    let big = "a".repeat(3_000_000);
    let start = std::time::Instant::now();
    assert!(find_sig_function(&big).is_none());
    assert!(find_n_function(&big).is_none());
    assert!(find_player_hash(&big).is_none());
    assert!(!has_q_array(&big));
    assert!(start.elapsed().as_secs() < 5);
}
