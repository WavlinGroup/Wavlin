//! Player-config JSON validation, ported from `PlayerConfigParser.kt`.
//!
//! Only parsing and validation live here. The n-transform JavaScript is built in Kotlin
//! (`PlayerConfigParser.buildNJsExpression`) from the validated `nClass`, never taken from the file,
//! and the final config map is assembled in Kotlin, so the cipher WebView receives exactly the
//! values it did before.
//!
//! Semantics copied from the Kotlin parser: file-level problems return `Failure`; an invalid single
//! entry is skipped and reported; any hash/alias collision rejects the whole file.
//!
//! Where serde_json and kotlinx.serialization could disagree, this crate does not decide: it returns
//! `Unsure` and Kotlin runs its own parser. That covers any text serde_json rejects (malformed JSON,
//! leading zeros, nesting past its depth limit) and any `schemaVersion` or `sts` number that is not
//! a plain integer literal (`0` or `-?[1-9][0-9]*`), such as `1.0`, `1e0` or `01`. For plain
//! integer literals both readers apply `toIntOrNull` semantics, so the results are identical.
//! Not checked against kotlinx here: duplicate object keys (serde_json keeps the last value at the
//! first key's position, which is what a `LinkedHashMap` put does).

use serde_json::{Number, Value};
use std::collections::HashSet;

pub const SUPPORTED_SCHEMA_VERSION: i32 = 1;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    pub hash: String,
    pub sig: String,
    pub n_class: String,
    pub sts: i32,
    pub aliases: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Outcome {
    Success { entries: Vec<Entry>, skipped: Vec<String> },
    Failure(String),
    /// The input is in a corner where the Kotlin parser must decide.
    Unsure,
}

struct Unsure;

fn is_ident_byte(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'$' || b == b'_'
}

/// `^[a-f0-9]{8}$`
fn is_hash(s: &str) -> bool {
    s.len() == 8 && s.bytes().all(|b| matches!(b, b'a'..=b'f' | b'0'..=b'9'))
}

/// `^[A-Za-z0-9$_]{1,8}$`
fn is_n_class(s: &str) -> bool {
    (1..=8).contains(&s.len()) && s.bytes().all(is_ident_byte)
}

fn take_digits(s: &str) -> Option<&str> {
    let n = s.bytes().take_while(u8::is_ascii_digit).count();
    (n > 0).then(|| &s[n..])
}

/// `^[A-Za-z0-9$_]{1,8}\(\d+,\d+,INPUT\)$`
fn is_sig(s: &str) -> bool {
    let name_len = s.bytes().take_while(|&b| is_ident_byte(b)).count();
    if !(1..=8).contains(&name_len) {
        return false;
    }
    let Some(rest) = s[name_len..].strip_prefix('(') else { return false };
    let Some(rest) = take_digits(rest) else { return false };
    let Some(rest) = rest.strip_prefix(',') else { return false };
    let Some(rest) = take_digits(rest) else { return false };
    rest == ",INPUT)"
}

/// `0` or `-?[1-9][0-9]*`, judged on the literal text (serde_json `arbitrary_precision`).
fn is_plain_int_literal(n: &Number) -> bool {
    let text = n.to_string();
    let digits = text.strip_prefix('-').unwrap_or(&text);
    match digits.as_bytes() {
        [b'0'] => true,
        [b'1'..=b'9', rest @ ..] => rest.iter().all(u8::is_ascii_digit),
        _ => false,
    }
}

/// `content.toIntOrNull()` for a JSON number: `Ok(None)` is out of `i32` range, `Err` is a literal
/// the two readers might treat differently.
fn int_of(n: &Number) -> Result<Option<i32>, Unsure> {
    if !is_plain_int_literal(n) {
        return Err(Unsure);
    }
    Ok(n.as_i64().and_then(|v| i32::try_from(v).ok()))
}

fn parse_aliases(value: Option<&Value>) -> Option<Vec<String>> {
    match value {
        None => Some(Vec::new()),
        Some(Value::Array(items)) => items
            .iter()
            .map(|item| item.as_str().filter(|a| is_hash(a)).map(str::to_owned))
            .collect(),
        Some(_) => None,
    }
}

/// `Ok(None)` means the entry is invalid and skipped, exactly as in Kotlin.
fn parse_entry(hash: &str, value: &Value) -> Result<Option<Entry>, Unsure> {
    let Some(obj) = value.as_object() else { return Ok(None) };
    if !is_hash(hash) {
        return Ok(None);
    }
    let Some(sig) = obj.get("sig").and_then(Value::as_str) else { return Ok(None) };
    if !is_sig(sig) {
        return Ok(None);
    }
    let Some(n_class) = obj.get("nClass").and_then(Value::as_str) else { return Ok(None) };
    if !is_n_class(n_class) {
        return Ok(None);
    }
    let sts = match obj.get("sts") {
        Some(Value::Number(n)) => int_of(n)?,
        _ => None,
    };
    let Some(sts) = sts.filter(|&n| n > 0) else { return Ok(None) };
    let Some(aliases) = parse_aliases(obj.get("aliases")) else { return Ok(None) };
    Ok(Some(Entry { hash: hash.to_owned(), sig: sig.to_owned(), n_class: n_class.to_owned(), sts, aliases }))
}

pub fn parse(json_text: &str) -> Outcome {
    parse_inner(json_text).unwrap_or(Outcome::Unsure)
}

fn parse_inner(json_text: &str) -> Result<Outcome, Unsure> {
    let Ok(root) = serde_json::from_str::<Value>(json_text) else { return Err(Unsure) };
    let Some(root) = root.as_object() else {
        return Ok(Outcome::Failure("root is not a JSON object".to_owned()));
    };

    let version = match root.get("schemaVersion") {
        Some(Value::Number(n)) => int_of(n)?,
        _ => None,
    };
    let Some(version) = version else {
        return Ok(Outcome::Failure("schemaVersion missing or not an int".to_owned()));
    };
    if version <= 0 {
        return Ok(Outcome::Failure("schemaVersion must be positive".to_owned()));
    }
    if version > SUPPORTED_SCHEMA_VERSION {
        return Ok(Outcome::Failure(format!(
            "unsupported schemaVersion {version} (supported: {SUPPORTED_SCHEMA_VERSION})"
        )));
    }

    let Some(players) = root.get("players").and_then(Value::as_object) else {
        return Ok(Outcome::Failure("players missing or not an object".to_owned()));
    };

    let mut entries = Vec::new();
    let mut skipped = Vec::new();
    let mut seen: HashSet<String> = HashSet::new();

    for (hash, element) in players {
        let Some(entry) = parse_entry(hash, element)? else {
            skipped.push(hash.clone());
            continue;
        };
        let mut keys: Vec<&str> = Vec::with_capacity(1 + entry.aliases.len());
        keys.push(&entry.hash);
        keys.extend(entry.aliases.iter().map(String::as_str));

        let duplicate = keys.iter().find(|k| seen.contains(**k)).copied().or_else(|| first_repeated(&keys));
        if let Some(dup) = duplicate {
            return Ok(Outcome::Failure(format!("duplicate hash/alias '{dup}' (entry {hash})")));
        }
        seen.extend(keys.iter().map(|k| (*k).to_owned()));
        entries.push(entry);
    }

    Ok(Outcome::Success { entries, skipped })
}

/// First key, in order of first appearance, that occurs more than once.
fn first_repeated<'a>(keys: &[&'a str]) -> Option<&'a str> {
    keys.iter()
        .enumerate()
        .find(|(i, k)| !keys[..*i].contains(k) && keys[*i + 1..].contains(k))
        .map(|(_, k)| *k)
}

/// Flat `String[]` wire format read by `NativePlayerConfig.decode` in Kotlin:
///
/// * `["U"]`: unsure, Kotlin parses
/// * `["F", reason]`
/// * `["S", skippedCount, skipped..., entryCount, (hash, sig, nClass, sts, aliasCount, aliases...)*]`
pub fn to_wire(outcome: &Outcome) -> Vec<String> {
    match outcome {
        Outcome::Unsure => vec!["U".to_owned()],
        Outcome::Failure(reason) => vec!["F".to_owned(), reason.clone()],
        Outcome::Success { entries, skipped } => {
            let mut out = vec!["S".to_owned(), skipped.len().to_string()];
            out.extend(skipped.iter().cloned());
            out.push(entries.len().to_string());
            for e in entries {
                out.push(e.hash.clone());
                out.push(e.sig.clone());
                out.push(e.n_class.clone());
                out.push(e.sts.to_string());
                out.push(e.aliases.len().to_string());
                out.extend(e.aliases.iter().cloned());
            }
            out
        }
    }
}

#[cfg(test)]
mod tests;
