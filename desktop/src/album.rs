//! Where the pictures of a post go: an album in the mosaic Telegram gives it, or a layout by count when the server
//! didn't say; a single picture in its own shape.
use crate::model::FeedMedia;

/// A picture's place, relative to the album's width: x and w from 0 to 1, y and h in the same unit.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Tile {
    pub x: f64,
    pub y: f64,
    pub w: f64,
    pub h: f64,
}

/// The space between pictures, about 2px on a 420px album.
const GAP: f64 = 0.005;
/// A picture of unknown shape.
const DEFAULT_SHAPE: f64 = 10.0 / 16.0;

/// One tile per picture, in order, and the album's height in the same unit.
pub fn layout(items: &[FeedMedia]) -> (Vec<Tile>, f64) {
    let placed = items.len() > 1 && items.iter().all(|m| m.x.is_some() && m.y.is_some() && m.w.is_some() && m.h.is_some());
    let tiles: Vec<Tile> = if placed {
        items.iter().map(|m| Tile { x: m.x.unwrap(), y: m.y.unwrap(), w: m.w.unwrap(), h: m.h.unwrap() }).collect()
    } else {
        by_count(items)
    };
    let height = tiles.iter().map(|t| t.y + t.h).fold(0.0, f64::max);
    (tiles, height)
}

fn by_count(items: &[FeedMedia]) -> Vec<Tile> {
    let t = |x, y, w, h| Tile { x, y, w, h };
    match items.len() {
        0 => vec![],
        1 => vec![t(0.0, 0.0, 1.0, items[0].h.filter(|h| *h > 0.0).unwrap_or(DEFAULT_SHAPE))],
        2 => {
            let w = (1.0 - GAP) / 2.0;
            vec![t(0.0, 0.0, w, w), t(w + GAP, 0.0, w, w)]
        }
        3 => {
            // one large on the left, two stacked on the right
            let big = (2.0 - GAP) / 3.0;
            let side = 1.0 - big - GAP;
            let half = (big - GAP) / 2.0;
            vec![t(0.0, 0.0, big, big), t(big + GAP, 0.0, side, half), t(big + GAP, half + GAP, side, half)]
        }
        4 => {
            // one across the top, three below
            let top = 2.0 / 3.0;
            let w = (1.0 - 2.0 * GAP) / 3.0;
            let mut out = vec![t(0.0, 0.0, 1.0, top)];
            out.extend((0..3).map(|i| t(i as f64 * (w + GAP), top + GAP, w, w)));
            out
        }
        n => {
            // two side by side, then rows of three
            let w2 = (1.0 - GAP) / 2.0;
            let h2 = w2 * 3.0 / 4.0;
            let w3 = (1.0 - 2.0 * GAP) / 3.0;
            let mut out = vec![t(0.0, 0.0, w2, h2), t(w2 + GAP, 0.0, w2, h2)];
            for i in 0..n - 2 {
                let (row, col) = (i / 3, i % 3);
                out.push(t(col as f64 * (w3 + GAP), h2 + GAP + row as f64 * (w3 + GAP), w3, w3));
            }
            out
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pic(place: Option<(f64, f64, f64, f64)>) -> FeedMedia {
        let (x, y, w, h) = match place {
            Some((x, y, w, h)) => (Some(x), Some(y), Some(w), Some(h)),
            None => (None, None, None, None),
        };
        FeedMedia { url: "https://cdn.example/1.jpg".into(), video: false, x, y, w, h }
    }

    fn overlap(a: &Tile, b: &Tile) -> bool {
        a.x < b.x + b.w - 1e-9 && b.x < a.x + a.w - 1e-9 && a.y < b.y + b.h - 1e-9 && b.y < a.y + a.h - 1e-9
    }

    #[test]
    fn telegrams_places_are_kept() {
        let items = [pic(Some((0.0, 0.0, 1.0, 0.6667))), pic(Some((0.0, 0.6711, 0.4967, 0.4437))), pic(Some((0.5011, 0.6711, 0.4989, 0.4437)))];
        let (tiles, height) = layout(&items);
        assert_eq!(tiles[1], Tile { x: 0.0, y: 0.6711, w: 0.4967, h: 0.4437 });
        assert!((height - 1.1148).abs() < 1e-9);
    }

    #[test]
    fn a_single_picture_keeps_its_shape() {
        assert_eq!(layout(&[pic(Some((0.0, 0.0, 1.0, 0.75)))]), (vec![Tile { x: 0.0, y: 0.0, w: 1.0, h: 0.75 }], 0.75));
        assert_eq!(layout(&[pic(None)]).1, DEFAULT_SHAPE);
    }

    #[test]
    fn without_places_every_count_gets_a_tidy_mosaic() {
        // one item without a place: none of them count
        let mut items = vec![pic(Some((0.0, 0.0, 0.5, 0.5))), pic(None)];
        assert_eq!(layout(&items).0[1].x, 0.5 + GAP / 2.0);
        for n in 2..=10 {
            items = (0..n).map(|_| pic(None)).collect();
            let (tiles, height) = layout(&items);
            assert_eq!(tiles.len(), n);
            for (i, a) in tiles.iter().enumerate() {
                assert!(a.x >= 0.0 && a.x + a.w <= 1.0 + 1e-9 && a.y >= 0.0 && a.y + a.h <= height + 1e-9, "{n} pictures: tile {i} {a:?} is outside");
                for b in &tiles[i + 1..] {
                    assert!(!overlap(a, b), "{n} pictures: {a:?} overlaps {b:?}");
                }
            }
            // the album is as wide as the message
            assert!((tiles.iter().map(|t| t.x + t.w).fold(0.0, f64::max) - 1.0).abs() < 1e-9, "{n} pictures");
        }
    }
}
