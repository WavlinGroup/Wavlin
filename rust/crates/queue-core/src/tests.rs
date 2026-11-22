use super::*;

fn is_perm(v: &[i32], n: i32) -> bool {
    is_permutation(v, n as usize)
}

#[test]
fn full_shuffle_is_permutation_with_current_first() {
    for seed in 0..500u64 {
        for (cur, total) in [(0, 1), (0, 5), (3, 5), (4, 5), (7, 20)] {
            let o = shuffle_order(cur, total, false, 0, seed).unwrap();
            assert!(is_perm(&o, total));
            assert_eq!(o[0], cur, "seed {seed}");
        }
    }
}

#[test]
fn out_of_range_current_keeps_permutation_without_swap() {
    let o = shuffle_order(99, 6, false, 0, 7).unwrap();
    assert!(is_perm(&o, 6));
    let o = shuffle_order(-1, 6, false, 0, 7).unwrap();
    assert!(is_perm(&o, 6));
}

#[test]
fn rejects_empty_queue() {
    assert!(shuffle_order(0, 0, false, 0, 1).is_none());
    assert!(shuffle_order(0, -3, true, 2, 1).is_none());
}

#[test]
fn playlist_first_current_in_originals() {
    for seed in 0..300u64 {
        let o = shuffle_order(2, 10, true, 5, seed).unwrap();
        assert!(is_perm(&o, 10));
        assert_eq!(o[0], 2);
        assert!(o[1..5].iter().all(|&v| v < 5 && v != 2), "originals next: {o:?}");
        assert!(o[5..].iter().all(|&v| v >= 5), "added last: {o:?}");
    }
}

#[test]
fn playlist_first_current_in_added() {
    for seed in 0..300u64 {
        let o = shuffle_order(7, 10, true, 5, seed).unwrap();
        assert!(is_perm(&o, 10));
        assert_eq!(o[0], 7);
        let mut originals = o[1..6].to_vec();
        originals.sort_unstable();
        assert_eq!(originals, vec![0, 1, 2, 3, 4]);
        assert!(o[6..].iter().all(|&v| v >= 5 && v != 7));
    }
}

#[test]
fn playlist_first_falls_back_to_full_shuffle_when_sizes_do_not_split() {
    for (orig, total) in [(0, 6), (6, 6), (9, 6)] {
        let o = shuffle_order(3, total, true, orig, 11).unwrap();
        assert!(is_perm(&o, total));
        assert_eq!(o[0], 3);
    }
    assert!(shuffle_order(50, 10, true, 5, 1).is_none());
}

#[test]
fn deterministic_per_seed_and_varies_across_seeds() {
    assert_eq!(shuffle_order(1, 12, false, 0, 42), shuffle_order(1, 12, false, 0, 42));
    let distinct: std::collections::HashSet<_> = (0..50u64).map(|s| shuffle_order(0, 12, false, 0, s).unwrap()).collect();
    assert!(distinct.len() > 40);
}

#[test]
fn shuffle_is_roughly_uniform() {
    // Position of element 0 across a 4-item shuffle: each slot should land near 25%.
    let mut counts = [0u32; 4];
    for seed in 0..20_000u64 {
        let mut v = [0, 1, 2, 3];
        Rng::new(seed).shuffle(&mut v);
        counts[v.iter().position(|&x| x == 0).unwrap()] += 1;
    }
    for c in counts {
        assert!((4_500..5_500).contains(&c), "{counts:?}");
    }
}

#[test]
fn play_next_places_new_items_right_after_current() {
    // Queue of 6 after inserting 2 items at index 3 (indices 3,4 are new). Old order of the other
    // four items was prev=[0], current=2, after=[5,1].
    // Old indices shift on insertion, so Kotlin passes the post-insertion traversal: it may still
    // contain the new indices, which must be dropped from prev/after.
    let o = play_next_order(&[0], &[5, 3, 1, 4], 2, 3, 2, 6).unwrap();
    assert_eq!(o, vec![0, 2, 3, 4, 5, 1]);
}

#[test]
fn play_next_appends_missing_indices_in_ascending_order() {
    let o = play_next_order(&[], &[], 1, 2, 2, 5).unwrap();
    assert_eq!(o, vec![1, 2, 3, 0, 4]);
}

#[test]
fn play_next_rejects_bad_input() {
    assert!(play_next_order(&[9], &[], 0, 1, 1, 4).is_none()); // out of range
    assert!(play_next_order(&[], &[], 5, 1, 1, 4).is_none()); // current out of range
    assert!(play_next_order(&[], &[], 0, 3, 3, 4).is_none()); // inserted range exceeds size
    assert!(play_next_order(&[1], &[1], 0, 2, 1, 4).is_none()); // duplicate index
    assert!(play_next_order(&[], &[], 0, 1, 1, 0).is_none());
    assert!(play_next_order(&[0, 1, 2, 3, 4], &[], 0, 0, 0, 3).is_none()); // out of range for size
}

#[test]
fn play_next_zero_items_keeps_order() {
    let o = play_next_order(&[1], &[2, 3], 0, 1, 0, 4).unwrap();
    assert_eq!(o, vec![1, 0, 2, 3]);
}
