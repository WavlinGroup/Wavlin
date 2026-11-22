use super::*;

fn ok(json: &str) -> (Vec<Entry>, Vec<String>) {
    match parse(json) {
        Outcome::Success { entries, skipped } => (entries, skipped),
        other => panic!("expected success, got {other:?}"),
    }
}

fn fail(json: &str) -> String {
    match parse(json) {
        Outcome::Failure(r) => r,
        other => panic!("expected failure, got {other:?}"),
    }
}

const GOOD: &str = r#"{"schemaVersion":1,"players":{"abcd1234":{"sig":"mP(4,155,INPUT)","nClass":"Yx","sts":20613}}}"#;

#[test]
fn parses_a_valid_entry() {
    let (entries, skipped) = ok(GOOD);
    assert!(skipped.is_empty());
    assert_eq!(
        entries,
        vec![Entry {
            hash: "abcd1234".into(),
            sig: "mP(4,155,INPUT)".into(),
            n_class: "Yx".into(),
            sts: 20613,
            aliases: vec![],
        }]
    );
}

#[test]
fn accepts_dollar_and_underscore_identifiers() {
    let (e, _) = ok(r#"{"schemaVersion":1,"players":{"abcd1234":{"sig":"$_a9(48,5831,INPUT)","nClass":"W_$","sts":1}}}"#);
    assert_eq!(e.len(), 1);
}

#[test]
fn file_level_failures() {
    assert_eq!(fail("[]"), "root is not a JSON object");
    assert_eq!(fail(r#"{"players":{}}"#), "schemaVersion missing or not an int");
    assert_eq!(fail(r#"{"schemaVersion":"1","players":{}}"#), "schemaVersion missing or not an int");
    assert_eq!(fail(r#"{"schemaVersion":true,"players":{}}"#), "schemaVersion missing or not an int");
    assert_eq!(fail(r#"{"schemaVersion":null,"players":{}}"#), "schemaVersion missing or not an int");
    assert_eq!(fail(r#"{"schemaVersion":0,"players":{}}"#), "schemaVersion must be positive");
    assert_eq!(fail(r#"{"schemaVersion":-1,"players":{}}"#), "schemaVersion must be positive");
    assert_eq!(fail(r#"{"schemaVersion":2,"players":{}}"#), "unsupported schemaVersion 2 (supported: 1)");
    assert_eq!(fail(r#"{"schemaVersion":1}"#), "players missing or not an object");
    assert_eq!(fail(r#"{"schemaVersion":1,"players":[]}"#), "players missing or not an object");
}

#[test]
fn ambiguous_inputs_defer_to_kotlin() {
    for json in [
        "not json",
        "",
        r#"{"schemaVersion":01,"players":{}}"#,
        r#"{"schemaVersion":1.0,"players":{}}"#,
        r#"{"schemaVersion":1e0,"players":{}}"#,
        r#"{"schemaVersion":+1,"players":{}}"#,
        r#"{"schemaVersion":1,"players":{"abcd1234":{"sig":"a(1,2,INPUT)","nClass":"A","sts":1.5}}}"#,
        r#"{"schemaVersion":1,"players":{"abcd1234":{"sig":"a(1,2,INPUT)","nClass":"A","sts":2e4}}}"#,
        r#"{"schemaVersion":1,"players":{"abcd1234":{"sig":"a(1,2,INPUT)","nClass":"A","sts":020613}}}"#,
        r#"{"schemaVersion":1,"players":{"abcd1234":{"sig":"a(1,2,INPUT)","nClass":"A","sts":1,}}}"#,
    ] {
        assert_eq!(parse(json), Outcome::Unsure, "input: {json}");
    }
}

#[test]
fn an_unreadable_sts_on_an_already_invalid_entry_is_not_ambiguous() {
    let (e, s) = ok(r#"{"schemaVersion":1,"players":{"abcd1234":{"sig":"bad","nClass":"A","sts":1.5}}}"#);
    assert!(e.is_empty());
    assert_eq!(s, ["abcd1234"]);
}

#[test]
fn plain_integer_literals_match_to_int_or_null() {
    assert_eq!(fail(r#"{"schemaVersion":-0,"players":{}}"#), "schemaVersion must be positive");
    let (e, _) = ok(r#"{"schemaVersion":1,"players":{"abcd1234":{"sig":"a(1,2,INPUT)","nClass":"A","sts":2147483647}}}"#);
    assert_eq!(e[0].sts, i32::MAX);
    let (e, s) = ok(r#"{"schemaVersion":1,"players":{"abcd1234":{"sig":"a(1,2,INPUT)","nClass":"A","sts":2147483648}}}"#);
    assert!(e.is_empty());
    assert_eq!(s, ["abcd1234"]);
}

#[test]
fn empty_players_is_success() {
    let (e, s) = ok(r#"{"schemaVersion":1,"players":{}}"#);
    assert!(e.is_empty() && s.is_empty());
}

fn skipped_for(entry: &str) -> Vec<String> {
    let json = format!(r#"{{"schemaVersion":1,"players":{{"abcd1234":{entry}}}}}"#);
    let (e, s) = ok(&json);
    assert!(e.is_empty(), "entry should have been skipped: {entry}");
    s
}

#[test]
fn bad_entries_are_skipped_not_fatal() {
    for entry in [
        "[]",
        "null",
        r#"{"nClass":"Yx","sts":1}"#,
        r#"{"sig":"mP(4,155,INPUT)","sts":1}"#,
        r#"{"sig":"mP(4,155,INPUT)","nClass":"Yx"}"#,
        r#"{"sig":"mP(4,155,x)","nClass":"Yx","sts":1}"#,
        r#"{"sig":"mP(4,155,INPUT);evil()","nClass":"Yx","sts":1}"#,
        r#"{"sig":"mP(4,155,INPUT)\n","nClass":"Yx","sts":1}"#,
        r#"{"sig":"toolongname(4,155,INPUT)","nClass":"Yx","sts":1}"#,
        r#"{"sig":"(4,155,INPUT)","nClass":"Yx","sts":1}"#,
        r#"{"sig":"mP(,155,INPUT)","nClass":"Yx","sts":1}"#,
        r#"{"sig":"mP(4,,INPUT)","nClass":"Yx","sts":1}"#,
        r#"{"sig":"mP(4,155,INPUT)","nClass":"","sts":1}"#,
        r#"{"sig":"mP(4,155,INPUT)","nClass":"a.b","sts":1}"#,
        r#"{"sig":"mP(4,155,INPUT)","nClass":"123456789","sts":1}"#,
        r#"{"sig":"mP(4,155,INPUT)","nClass":"Yx","sts":"1"}"#,
        r#"{"sig":"mP(4,155,INPUT)","nClass":"Yx","sts":0}"#,
        r#"{"sig":"mP(4,155,INPUT)","nClass":"Yx","sts":-5}"#,
        r#"{"sig":"mP(4,155,INPUT)","nClass":"Yx","sts":3000000000}"#,
        r#"{"sig":"mP(4,155,INPUT)","nClass":"Yx","sts":true}"#,
        r#"{"sig":"mP(4,155,INPUT)","nClass":"Yx","sts":null}"#,
        r#"{"sig":"mP(4,155,INPUT)","nClass":"Yx","sts":1,"aliases":null}"#,
        r#"{"sig":"mP(4,155,INPUT)","nClass":"Yx","sts":1,"aliases":"abcd5678"}"#,
        r#"{"sig":"mP(4,155,INPUT)","nClass":"Yx","sts":1,"aliases":[1]}"#,
        r#"{"sig":"mP(4,155,INPUT)","nClass":"Yx","sts":1,"aliases":["ABCD5678"]}"#,
        r#"{"sig":"mP(4,155,INPUT)","nClass":"Yx","sts":1,"aliases":["abcd567"]}"#,
    ] {
        assert_eq!(skipped_for(entry), vec!["abcd1234".to_owned()], "entry: {entry}");
    }
}

#[test]
fn bad_hash_key_is_skipped() {
    for key in ["ABCD1234", "abcd123", "abcd12345", "abcdefgh", ""] {
        let json = format!(
            r#"{{"schemaVersion":1,"players":{{"{key}":{{"sig":"mP(4,155,INPUT)","nClass":"Yx","sts":1}}}}}}"#
        );
        let (e, s) = ok(&json);
        assert!(e.is_empty());
        assert_eq!(s, vec![key.to_owned()]);
    }
}

#[test]
fn one_bad_entry_does_not_poison_the_rest() {
    let (e, s) = ok(
        r#"{"schemaVersion":1,"players":{
            "aaaaaaaa":{"sig":"a(1,2,INPUT)","nClass":"A","sts":1},
            "bbbbbbbb":{"sig":"bad","nClass":"B","sts":2},
            "cccccccc":{"sig":"c(3,4,INPUT)","nClass":"C","sts":3}}}"#,
    );
    assert_eq!(e.iter().map(|x| x.hash.as_str()).collect::<Vec<_>>(), ["aaaaaaaa", "cccccccc"]);
    assert_eq!(s, ["bbbbbbbb"]);
}

#[test]
fn aliases_are_returned() {
    let (e, _) = ok(
        r#"{"schemaVersion":1,"players":{"abcd1234":{"sig":"a(1,2,INPUT)","nClass":"A","sts":1,"aliases":["11112222","33334444"]}}}"#,
    );
    assert_eq!(e[0].aliases, ["11112222", "33334444"]);
}

#[test]
fn collisions_reject_the_whole_file() {
    let own_alias = r#"{"schemaVersion":1,"players":{"abcd1234":{"sig":"a(1,2,INPUT)","nClass":"A","sts":1,"aliases":["abcd1234"]}}}"#;
    assert_eq!(fail(own_alias), "duplicate hash/alias 'abcd1234' (entry abcd1234)");

    let repeated_alias = r#"{"schemaVersion":1,"players":{"abcd1234":{"sig":"a(1,2,INPUT)","nClass":"A","sts":1,"aliases":["11112222","11112222"]}}}"#;
    assert_eq!(fail(repeated_alias), "duplicate hash/alias '11112222' (entry abcd1234)");

    let cross = r#"{"schemaVersion":1,"players":{
        "aaaaaaaa":{"sig":"a(1,2,INPUT)","nClass":"A","sts":1,"aliases":["cccccccc"]},
        "bbbbbbbb":{"sig":"b(1,2,INPUT)","nClass":"B","sts":2,"aliases":["cccccccc"]}}}"#;
    assert_eq!(fail(cross), "duplicate hash/alias 'cccccccc' (entry bbbbbbbb)");

    let alias_is_other_hash = r#"{"schemaVersion":1,"players":{
        "aaaaaaaa":{"sig":"a(1,2,INPUT)","nClass":"A","sts":1},
        "bbbbbbbb":{"sig":"b(1,2,INPUT)","nClass":"B","sts":2,"aliases":["aaaaaaaa"]}}}"#;
    assert_eq!(fail(alias_is_other_hash), "duplicate hash/alias 'aaaaaaaa' (entry bbbbbbbb)");
}

#[test]
fn a_skipped_entry_reserves_no_keys() {
    let (e, s) = ok(
        r#"{"schemaVersion":1,"players":{
            "aaaaaaaa":{"sig":"bad","nClass":"A","sts":1,"aliases":["bbbbbbbb"]},
            "bbbbbbbb":{"sig":"b(1,2,INPUT)","nClass":"B","sts":2}}}"#,
    );
    assert_eq!(e.len(), 1);
    assert_eq!(s, ["aaaaaaaa"]);
}

#[test]
fn wire_unsure() {
    assert_eq!(to_wire(&Outcome::Unsure), ["U"]);
}

#[test]
fn wire_failure() {
    assert_eq!(to_wire(&Outcome::Failure("x".into())), ["F", "x"]);
}

#[test]
fn wire_success_layout() {
    let outcome = parse(
        r#"{"schemaVersion":1,"players":{
            "aaaaaaaa":{"sig":"a(1,2,INPUT)","nClass":"A","sts":7,"aliases":["11112222"]},
            "bbbbbbbb":{"sig":"nope","nClass":"B","sts":2}}}"#,
    );
    assert_eq!(
        to_wire(&outcome),
        ["S", "1", "bbbbbbbb", "1", "aaaaaaaa", "a(1,2,INPUT)", "A", "7", "1", "11112222"]
    );
}
