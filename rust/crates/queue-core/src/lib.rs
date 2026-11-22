//! Shuffle-order permutations, ported from `MusicService.kt`.
//!
//! Pure functions: player state, timeline traversal and `DefaultShuffleOrder` construction stay in
//! Kotlin. Every function returns `None` for input it cannot turn into a valid permutation, and
//! Kotlin then runs its original code. Randomness comes from a caller-supplied seed, so results are
//! reproducible in tests; the Kotlin shuffle was unseeded and its exact sequences are not matched.

/// SplitMix64. Not for cryptography; used only to order a playback queue.
pub struct Rng(u64);

impl Rng {
    pub fn new(seed: u64) -> Self {
        Rng(seed)
    }

    fn next_u64(&mut self) -> u64 {
        self.0 = self.0.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.0;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }

    /// Uniform in `0..n` by rejection, so there is no modulo bias. `n` must be non-zero.
    fn below(&mut self, n: u64) -> u64 {
        let limit = u64::MAX - (u64::MAX % n);
        loop {
            let v = self.next_u64();
            if v < limit {
                return v % n;
            }
        }
    }

    pub fn shuffle<T>(&mut self, items: &mut [T]) {
        for i in (1..items.len()).rev() {
            let j = self.below(i as u64 + 1) as usize;
            items.swap(i, j);
        }
    }
}

fn is_permutation(order: &[i32], size: usize) -> bool {
    if order.len() != size {
        return false;
    }
    let mut seen = vec![false; size];
    for &v in order {
        let Ok(i) = usize::try_from(v) else { return false };
        if i >= size || seen[i] {
            return false;
        }
        seen[i] = true;
    }
    true
}

/// New shuffle order for a queue of `total` items with `current` playing.
///
/// Mirrors `applyShuffleOrder`. With `playlist_first` and `0 < original_size < total`, the original
/// items and the added items are shuffled separately: when `current` is an original item the order
/// is `current`, the other originals, then the added items; when `current` is an added item it is
/// `current`, all originals, then the other added items. Otherwise the whole queue is shuffled and
/// `current` is swapped to the front, unchanged if it is out of range.
pub fn shuffle_order(current: i32, total: i32, playlist_first: bool, original_size: i32, seed: u64) -> Option<Vec<i32>> {
    if total <= 0 {
        return None;
    }
    let mut rng = Rng::new(seed);
    let out = if playlist_first && original_size > 0 && original_size < total {
        if !(0..total).contains(&current) {
            return None;
        }
        let mut originals: Vec<i32> = (0..original_size).filter(|&i| i != current).collect();
        let mut added: Vec<i32> = (original_size..total).filter(|&i| i != current).collect();
        rng.shuffle(&mut originals);
        rng.shuffle(&mut added);
        let mut out = Vec::with_capacity(total as usize);
        out.push(current);
        if current < original_size {
            out.extend(originals);
        } else {
            let mut all_originals: Vec<i32> = (0..original_size).collect();
            rng.shuffle(&mut all_originals);
            out.extend(all_originals);
        }
        out.extend(added);
        out
    } else {
        let mut out: Vec<i32> = (0..total).collect();
        rng.shuffle(&mut out);
        if let Some(pos) = out.iter().position(|&v| v == current) {
            out.swap(0, pos);
        }
        out
    };
    is_permutation(&out, total as usize).then_some(out)
}

/// Shuffle order after inserting `item_count` items at `insert_index` so they play next.
///
/// `prev` is the old backward traversal in forward order and `after` the old forward traversal, both
/// excluding `current` (Kotlin builds them from the timeline). Result: `prev`, `current`, the new
/// items in insertion order, then `after`, with the new indices removed from `prev` and `after`.
/// Indices still missing are appended in ascending order. `None` if any index is out of range or
/// the result is not a permutation of `0..size`.
pub fn play_next_order(prev: &[i32], after: &[i32], current: i32, insert_index: i32, item_count: i32, size: i32) -> Option<Vec<i32>> {
    if size <= 0 || item_count < 0 || insert_index < 0 || !(0..size).contains(&current) {
        return None;
    }
    if i64::from(insert_index) + i64::from(item_count) > i64::from(size) {
        return None;
    }
    let new_end = insert_index + item_count;
    let is_new = |v: i32| v >= insert_index && v < new_end;
    let in_range = |v: &i32| (0..size).contains(v);
    if !prev.iter().all(in_range) || !after.iter().all(in_range) {
        return None;
    }

    let mut out: Vec<i32> = Vec::with_capacity(size as usize);
    out.extend(prev.iter().copied().filter(|&v| !is_new(v)));
    out.push(current);
    out.extend(insert_index..new_end);
    out.extend(after.iter().copied().filter(|&v| !is_new(v)));

    if out.len() < size as usize {
        let mut present = vec![false; size as usize];
        for &v in &out {
            present[v as usize] = true;
        }
        out.extend((0..size).filter(|&i| !present[i as usize]));
    }
    is_permutation(&out, size as usize).then_some(out)
}

#[cfg(test)]
mod tests;
