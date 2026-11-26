//! player.js scanning, ported from `FunctionNameExtractor.kt`.
//!
//! Only the regex heuristics live here. Config lookup, precedence between config and heuristics,
//! the MD5 fallback hash and logging stay in Kotlin, so a validated config can never be shadowed by
//! a heuristic and the cipher WebView still receives exactly the values it did before.
//!
//! Pattern text is copied from the Kotlin sources. `\s`, `\d` and `\b` use the regex crate's Unicode
//! definitions, the closest match to Android's ICU-based `java.util.regex`; a difference would only
//! show on exotic whitespace or digits inside player.js and has not been checked against a real
//! player.js.

use regex::Regex;
use std::sync::OnceLock;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SigMatch {
    pub name: String,
    pub constant_arg: Option<i32>,
    pub pattern_index: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NMatch {
    pub name: String,
    pub array_index: Option<i32>,
    pub pattern_index: usize,
}

fn compile_all(patterns: &[&str]) -> Vec<Regex> {
    patterns
        .iter()
        .map(|p| Regex::new(p).unwrap_or_else(|e| panic!("invalid built-in pattern {p}: {e}")))
        .collect()
}

fn one(cell: &'static OnceLock<Regex>, pattern: &str) -> &'static Regex {
    cell.get_or_init(|| Regex::new(pattern).unwrap_or_else(|e| panic!("invalid built-in pattern {pattern}: {e}")))
}

fn int(text: &str) -> Option<i32> {
    text.parse::<i32>().ok()
}

// ---------------------------------------------------------------- Q-array

pub fn has_q_array(js: &str) -> bool {
    static R: OnceLock<Regex> = OnceLock::new();
    one(&R, r#"var\s+Q\s*=\s*"[^"]+"\s*\.\s*split\s*\(\s*"\}"\s*\)"#).is_match(js)
}

// ---------------------------------------------------------------- player hash

/// First embedded 8-hex player hash, trying the three URL patterns in order. `None` means the
/// caller should compute its content hash.
pub fn find_player_hash(js: &str) -> Option<String> {
    static R: OnceLock<Vec<Regex>> = OnceLock::new();
    let patterns = R.get_or_init(|| {
        compile_all(&[
            r#"jsUrl['":\s]+[^"']*?/player/([a-f0-9]{8})/"#,
            r#"player_ias\.vflset/[^/]+/([a-f0-9]{8})/"#,
            r#"/s/player/([a-f0-9]{8})/"#,
        ])
    });
    patterns
        .iter()
        .find_map(|re| re.captures(js).and_then(|c| c.get(1)).map(|m| m.as_str().to_owned()))
}

// ---------------------------------------------------------------- signatureTimestamp

/// Value of the player's own `signatureTimestamp` literal.
pub fn find_anchored_sts(js: &str) -> Option<i32> {
    static R: OnceLock<Regex> = OnceLock::new();
    let caps = one(&R, r#"signatureTimestamp['":\s]+(\d+)"#).captures(js)?;
    int(caps.get(1)?.as_str())
}

/// Loose `sts` pattern. It can false-match anywhere in a 2 MB script, so callers use it last.
pub fn find_loose_sts(js: &str) -> Option<i32> {
    static R: OnceLock<Regex> = OnceLock::new();
    let caps = one(&R, r#"sts['":\s]+(\d+)"#).captures(js)?;
    int(caps.get(1)?.as_str())
}

// ---------------------------------------------------------------- signature function

pub fn find_sig_function(js: &str) -> Option<SigMatch> {
    static R: OnceLock<Vec<Regex>> = OnceLock::new();
    let patterns = R.get_or_init(|| {
        compile_all(&[
            // 0: &&(VAR=FUNC(NUM,decodeURIComponent(VAR)
            r#"&&\s*\(\s*[a-zA-Z0-9$]+\s*=\s*([a-zA-Z0-9$]+)\s*\(\s*(\d+)\s*,\s*decodeURIComponent\s*\(\s*[a-zA-Z0-9$]+\s*\)"#,
            // 1: &&(z=hJ(6,decodeURIComponent(h.s)
            r#"&&\s*\(\s*[a-zA-Z0-9$]+\s*=\s*([a-zA-Z0-9$]+)\s*\(\s*(\d+)\s*,\s*decodeURIComponent\s*\(\s*[a-zA-Z0-9$]+\s*\.\s*[a-z]\s*\)"#,
            // 2..6: classic patterns
            r#"\b[cs]\s*&&\s*[adf]\.set\([^,]+\s*,\s*encodeURIComponent\(([a-zA-Z0-9$]+)\("#,
            r#"\b[a-zA-Z0-9]+\s*&&\s*[a-zA-Z0-9]+\.set\([^,]+\s*,\s*encodeURIComponent\(([a-zA-Z0-9$]+)\("#,
            r#"\bm=([a-zA-Z0-9$]{2,})\(decodeURIComponent\(h\.s\)\)"#,
            r#"\bc\s*&&\s*d\.set\([^,]+\s*,\s*(?:encodeURIComponent\s*\()([a-zA-Z0-9$]+)\("#,
            r#"\bc\s*&&\s*[a-z]\.set\([^,]+\s*,\s*encodeURIComponent\(([a-zA-Z0-9$]+)\("#,
        ])
    });
    for (index, re) in patterns.iter().enumerate() {
        if let Some(caps) = re.captures(js) {
            let name = caps.get(1)?.as_str().to_owned();
            // Kotlin reads group 2 whenever the pattern has one; only patterns 0 and 1 do.
            let constant_arg = caps.get(2).and_then(|m| int(m.as_str()));
            return Some(SigMatch { name, constant_arg, pattern_index: index });
        }
    }
    None
}

// ---------------------------------------------------------------- n function

pub fn find_n_function(js: &str) -> Option<NMatch> {
    static P0: OnceLock<Regex> = OnceLock::new();
    static P1: OnceLock<Regex> = OnceLock::new();
    static P3: OnceLock<Regex> = OnceLock::new();
    static P4: OnceLock<Regex> = OnceLock::new();

    // 0: .get("n"))&&(b=FUNC[IDX](VAR)
    let p0 = one(&P0, r#"\.get\("n"\)\)&&\(b=([a-zA-Z0-9$]+)(?:\[(\d+)\])?\(([a-zA-Z0-9])\)"#);
    if let Some(c) = p0.captures(js) {
        return Some(NMatch {
            name: c.get(1)?.as_str().to_owned(),
            array_index: c.get(2).and_then(|m| int(m.as_str())),
            pattern_index: 0,
        });
    }

    // 1: .get("n"))&&(FUNC=VAR[IDX](FUNC). The Kotlin pattern ends in a backreference, which the
    // regex crate lacks: capture the argument as group 4 and require it to equal group 1. Groups 1
    // and 2 each end at a non-identifier character, so a failed check cannot be rescued by a
    // different split at the same start and moving on to the next match is equivalent.
    let p1 = one(
        &P1,
        r#"\.get\("n"\)\)\s*&&\s*\(([a-zA-Z0-9$]+)\s*=\s*([a-zA-Z0-9$]+)(?:\[(\d+)\])?\(([a-zA-Z0-9$]+)\)"#,
    );
    for c in p1.captures_iter(js) {
        if c.get(1).map(|m| m.as_str()) == c.get(4).map(|m| m.as_str()) {
            return Some(NMatch {
                name: c.get(2)?.as_str().to_owned(),
                array_index: c.get(3).and_then(|m| int(m.as_str())),
                pattern_index: 1,
            });
        }
    }

    // 2 (April 2026 `.get("n");if(m){...match`) has no capture group and Kotlin skips it, so it is
    // not ported.

    // 3: (VAR=String.fromCharCode(110)
    let p3 = one(&P3, r#"\(\s*([a-zA-Z0-9$]+)\s*=\s*String\.fromCharCode\(110\)"#);
    if let Some(c) = p3.captures(js) {
        return Some(NMatch { name: c.get(1)?.as_str().to_owned(), array_index: None, pattern_index: 3 });
    }

    // 4: FUNC=function(x){...enhanced_except_
    let p4 = one(&P4, r#"([a-zA-Z0-9$]+)\s*=\s*function\([a-zA-Z0-9]\)\s*\{[^}]*?enhanced_except_"#);
    if let Some(c) = p4.captures(js) {
        return Some(NMatch { name: c.get(1)?.as_str().to_owned(), array_index: None, pattern_index: 4 });
    }
    None
}

#[cfg(test)]
mod tests;
