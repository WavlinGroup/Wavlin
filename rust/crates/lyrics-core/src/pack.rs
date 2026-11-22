//! Flat, column-wise form of parsed lyrics, so the JNI layer can hand them to Kotlin as a few
//! primitive and string arrays instead of one object per entry and word.

use crate::parse::Entry;

/// Column `i` of the entry arrays describes entry `i`. Words of all entries are concatenated in
/// the word arrays; `word_counts[i]` says how many belong to entry `i`, or `-1` when the entry
/// has no word timings (`None`), which is different from `0` (`Some` and empty).
#[derive(Debug, Default, Clone, PartialEq)]
pub struct Packed {
    pub times: Vec<i64>,
    pub texts: Vec<String>,
    pub agents: Vec<Option<String>>,
    pub is_background: Vec<bool>,
    pub word_counts: Vec<i32>,
    pub word_texts: Vec<String>,
    pub word_starts: Vec<f64>,
    pub word_ends: Vec<f64>,
    pub word_trailing_spaces: Vec<bool>,
}

/// `None` when a count does not fit the `i32` the JVM arrays use.
pub fn pack(entries: &[Entry]) -> Option<Packed> {
    let mut p = Packed::default();
    for e in entries {
        p.times.push(e.time);
        p.texts.push(e.text.clone());
        p.agents.push(e.agent.clone());
        p.is_background.push(e.is_background);
        match &e.words {
            None => p.word_counts.push(-1),
            Some(words) => {
                p.word_counts.push(i32::try_from(words.len()).ok()?);
                for w in words {
                    p.word_texts.push(w.text.clone());
                    p.word_starts.push(w.start);
                    p.word_ends.push(w.end);
                    p.word_trailing_spaces.push(w.trailing_space);
                }
            }
        }
    }
    i32::try_from(p.word_texts.len()).ok()?;
    i32::try_from(p.times.len()).ok()?;
    Some(p)
}
