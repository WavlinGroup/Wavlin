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
//! Known, unverified differences from kotlinx.serialization's JSON reader: malformed-JSON reason
//! text, nesting depth limit (serde_json stops at 128 and returns `Failure`), and number literals
//! with leading zeros or `-0`, which serde_json rejects or reads as non-integers. Reason strings are
//! only logged. None of this has been checked against a real remote config.

use serde_json::Value;
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
}

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

fn string_of(v: &Value) -> Option<&str> {
    v.as_str()
}

fn parse_entry(hash: &str, value: &Value) -> Option<Entry> {
    let obj = value.as_object()?;
    if !is_hash(hash) {
        return None;
    }
    let sig = string_of(obj.get("sig")?)?;
    if !is_sig(sig) {
        return None;
    }
    let n_class = string_of(obj.get("nClass")?)?;
    if !is_n_class(n_class) {
        return None;
    }
    let sts = int_of(obj.get("sts")?)?;
    if sts <= 0 {
        return None;
    }
    let aliases = match obj.get("aliases") {
        None => Vec::new(),
        Some(Value::Array(items)) => {
            let mut out = Vec::with_capacity(items.len());
            for item in items {
                let alias = string_of(item)?;
                if !is_hash(alias) {
                    return None;
                }
                out.push(alias.to_owned());
            }
            out
        }
        Some(_) => return None,
    };
    Some(Entry { hash: hash.to_owned(), sig: sig.to_owned(), n_class: n_class.to_owned(), sts, aliases })
}

/// An integer JSON number that fits `i32`. Strings, booleans, null, fractions and exponent forms are
/// all rejected, matching `content.toIntOrNull()` on a non-string primitive.
fn int_of(v: &Value) -> Option<i32> {
    match v {
        Value::Number(n) => i32::try_from(n.as_i64()?).ok(),
        _ => None,
    }
}

pub fn parse(json_text: &str) -> Outcome {
    let root: Value = match serde_json::from_str(json_text) {
        Ok(v) => v,
        Err(e) => return Outcome::Failure(format!("malformed JSON: {e}")),
    };
    let Some(root) = root.as_object() else {
        return Outcome::Failure("root is not a JSON object".to_owned());
    };

    let version = match root.get("schemaVersion") {
        Some(v @ (Value::Number(_) | Value::Bool(_) | Value::Null)) => int_of(v),
        _ => None,
    };
    let Some(version) = version else {
        return Outcome::Failure("schemaVersion missing or not an int".to_owned());
    };
    if version <= 0 {
        return Outcome::Failure("schemaVersion must be positive".to_owned());
    }
    if version > SUPPORTED_SCHEMA_VERSION {
        return Outcome::Failure(format!(
            "unsupported schemaVersion {version} (supported: {SUPPORTED_SCHEMA_VERSION})"
        ));
    }

    let Some(players) = root.get("players").and_then(Value::as_object) else {
        return Outcome::Failure("players missing or not an object".to_owned());
    };

    let mut entries = Vec::new();
    let mut skipped = Vec::new();
    let mut seen: HashSet<String> = HashSet::new();

    for (hash, element) in players {
        let Some(entry) = parse_entry(hash, element) else {
            skipped.push(hash.clone());
            continue;
        };
        let mut keys: Vec<&str> = Vec::with_capacity(1 + entry.aliases.len());
        keys.push(&entry.hash);
        keys.extend(entry.aliases.iter().map(String::as_str));

        let duplicate = keys.iter().find(|k| seen.contains(**k)).copied().or_else(|| first_repeated(&keys));
        if let Some(dup) = duplicate {
            return Outcome::Failure(format!("duplicate hash/alias '{dup}' (entry {hash})"));
        }
        seen.extend(keys.iter().map(|k| (*k).to_owned()));
        entries.push(entry);
    }

    Outcome::Success { entries, skipped }
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
/// * `["F", reason]`
/// * `["S", skippedCount, skipped..., entryCount, (hash, sig, nClass, sts, aliasCount, aliases...)*]`
pub fn to_wire(outcome: &Outcome) -> Vec<String> {
    match outcome {
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
