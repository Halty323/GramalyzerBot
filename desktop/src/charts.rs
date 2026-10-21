//! Charts drawn on a canvas: the audience line, the posting-time heatmap and the watchlist's sparklines.
use crate::theme::tokens;
use iced::mouse;
use iced::widget::canvas::{self, Frame, Geometry, Path, Stroke, Text};
use iced::{Color, Font, Pixels, Point, Rectangle, Renderer, Size, Theme, alignment};

/// A line chart with a hover readout. `labels` are the x-axis texts (first and last are drawn), `tip` the readout per point.
pub struct LineChart {
    pub values: Vec<f64>,
    pub labels: Vec<String>,
    pub tips: Vec<String>,
    pub format: fn(f64) -> String,
}

#[derive(Default)]
pub struct Hover(Option<Point>);

impl<M> canvas::Program<M> for LineChart {
    type State = Hover;

    fn update(&self, state: &mut Hover, event: &canvas::Event, bounds: Rectangle, cursor: mouse::Cursor) -> Option<canvas::Action<M>> {
        if let canvas::Event::Mouse(mouse::Event::CursorMoved { .. } | mouse::Event::CursorLeft) = event {
            let p = cursor.position_in(bounds);
            if p != state.0 {
                state.0 = p;
                return Some(canvas::Action::request_redraw());
            }
        }
        None
    }

    fn draw(&self, state: &Hover, renderer: &Renderer, theme: &Theme, bounds: Rectangle, _cursor: mouse::Cursor) -> Vec<Geometry> {
        let t = tokens(theme);
        let mut frame = Frame::new(renderer, bounds.size());
        let (left, right, top, bottom) = (52.0, 12.0, 12.0, 26.0);
        let w = bounds.width - left - right;
        let h = bounds.height - top - bottom;
        if self.values.len() < 2 || w <= 0.0 || h <= 0.0 {
            return vec![frame.into_geometry()];
        }
        let min = self.values.iter().cloned().fold(f64::MAX, f64::min);
        let max = self.values.iter().cloned().fold(f64::MIN, f64::max);
        let pad = ((max - min) * 0.15).max(1.0);
        let (lo, hi) = ((min - pad).max(0.0), max + pad);
        let x = |i: usize| left + w * i as f32 / (self.values.len() - 1) as f32;
        let y = |v: f64| top + h - ((v - lo) / (hi - lo)) as f32 * h;

        for k in 0..4 {
            let gy = top + h * k as f32 / 3.0;
            frame.stroke(&Path::line(Point::new(left, gy), Point::new(left + w, gy)), Stroke::default().with_color(t.border).with_width(1.0));
            let v = hi - (hi - lo) * k as f64 / 3.0;
            frame.fill_text(label((self.format)(v), Point::new(left - 8.0, gy), t.faint, alignment::Horizontal::Right));
        }
        let line = Path::new(|b| {
            for (i, v) in self.values.iter().enumerate() {
                let p = Point::new(x(i), y(*v));
                if i == 0 { b.move_to(p) } else { b.line_to(p) }
            }
        });
        let area = Path::new(|b| {
            b.move_to(Point::new(x(0), top + h));
            for (i, v) in self.values.iter().enumerate() {
                b.line_to(Point::new(x(i), y(*v)));
            }
            b.line_to(Point::new(x(self.values.len() - 1), top + h));
            b.close();
        });
        frame.fill(&area, Color { a: 0.10, ..t.accent });
        frame.stroke(&line, Stroke::default().with_color(t.accent).with_width(2.5).with_line_join(canvas::LineJoin::Round));
        if let (Some(first), Some(last)) = (self.labels.first(), self.labels.last()) {
            frame.fill_text(label(first.clone(), Point::new(left, top + h + 15.0), t.faint, alignment::Horizontal::Left));
            frame.fill_text(label(last.clone(), Point::new(left + w, top + h + 15.0), t.faint, alignment::Horizontal::Right));
        }
        if let Some(p) = state.0 {
            if p.x >= left - 6.0 && p.x <= left + w + 6.0 {
                let i = (((p.x - left) / w) * (self.values.len() - 1) as f32).round().clamp(0.0, (self.values.len() - 1) as f32) as usize;
                let (px, py) = (x(i), y(self.values[i]));
                frame.stroke(&Path::line(Point::new(px, top), Point::new(px, top + h)), Stroke::default().with_color(t.faint).with_width(1.0));
                frame.fill(&Path::circle(Point::new(px, py), 5.0), t.surface);
                frame.stroke(&Path::circle(Point::new(px, py), 5.0), Stroke::default().with_color(t.accent).with_width(2.5));
                let tip = self.tips.get(i).cloned().unwrap_or_default();
                let tw = 9.0 + tip.chars().count() as f32 * 6.8;
                let tx = (px - tw / 2.0).clamp(0.0, bounds.width - tw);
                let ty = (py - 36.0).max(0.0);
                frame.fill(&Path::rounded_rectangle(Point::new(tx, ty), Size::new(tw, 24.0), 6.0.into()), t.text);
                frame.fill_text(label(tip, Point::new(tx + tw / 2.0, ty + 12.0), t.surface, alignment::Horizontal::Center));
            }
        }
        vec![frame.into_geometry()]
    }
}

fn label(content: String, position: Point, color: Color, align: alignment::Horizontal) -> Text {
    Text {
        // A left-to-right mark first: canvas text has no width limit, and iced 0.14's software renderer
        // places a right-to-left paragraph (an Arabic or Persian date) at x = infinity.
        content: format!("\u{200E}{content}"),
        position,
        color,
        size: Pixels(11.5),
        font: Font::with_name("Inter"),
        align_x: align.into(),
        align_y: alignment::Vertical::Center,
        ..Text::default()
    }
}

/// When a channel's posts do best: 7 rows (Monday first) × 24 hours, shaded by lift.
pub struct Heatmap {
    pub lift: Vec<Vec<f64>>,
    pub days: Vec<String>,
    /// The readout for a hovered cell (day, hour).
    pub tip: Box<dyn Fn(usize, usize) -> String>,
}

impl<M> canvas::Program<M> for Heatmap {
    type State = Hover;

    fn update(&self, state: &mut Hover, event: &canvas::Event, bounds: Rectangle, cursor: mouse::Cursor) -> Option<canvas::Action<M>> {
        if let canvas::Event::Mouse(mouse::Event::CursorMoved { .. } | mouse::Event::CursorLeft) = event {
            let p = cursor.position_in(bounds);
            if p != state.0 {
                state.0 = p;
                return Some(canvas::Action::request_redraw());
            }
        }
        None
    }

    fn draw(&self, state: &Hover, renderer: &Renderer, theme: &Theme, bounds: Rectangle, _cursor: mouse::Cursor) -> Vec<Geometry> {
        let t = tokens(theme);
        let mut frame = Frame::new(renderer, bounds.size());
        let (left, top) = (44.0, 18.0);
        let cw = ((bounds.width - left) / 24.0).max(4.0);
        let ch = ((bounds.height - top) / 7.0).max(4.0);
        let max = self.lift.iter().flatten().cloned().fold(1.0, f64::max);
        for h in (0..24).step_by(3) {
            frame.fill_text(label(format!("{h:02}"), Point::new(left + cw * h as f32 + cw / 2.0, 8.0), t.faint, alignment::Horizontal::Center));
        }
        for (d, row) in self.lift.iter().enumerate().take(7) {
            frame.fill_text(label(self.days.get(d).cloned().unwrap_or_default(), Point::new(0.0, top + ch * d as f32 + ch / 2.0), t.muted, alignment::Horizontal::Left));
            for (h, v) in row.iter().enumerate().take(24) {
                let k = ((v - 0.5) / (max - 0.5)).clamp(0.0, 1.0) as f32;
                let color = Color { a: 0.08 + 0.92 * k, ..t.accent };
                let p = Point::new(left + cw * h as f32 + 1.5, top + ch * d as f32 + 1.5);
                frame.fill(&Path::rounded_rectangle(p, Size::new(cw - 3.0, ch - 3.0), 4.0.into()), color);
            }
        }
        if let Some(p) = state.0 {
            if p.x > left && p.y > top {
                let h = ((p.x - left) / cw) as usize;
                let d = ((p.y - top) / ch) as usize;
                if h < 24 && d < 7 {
                    let cell = Point::new(left + cw * h as f32 + 1.5, top + ch * d as f32 + 1.5);
                    frame.stroke(&Path::rounded_rectangle(cell, Size::new(cw - 3.0, ch - 3.0), 4.0.into()), Stroke::default().with_color(t.text).with_width(1.5));
                    let tip = (self.tip)(d, h);
                    let tw = 12.0 + tip.chars().count() as f32 * 6.8;
                    let tx = (p.x - tw / 2.0).clamp(0.0, bounds.width - tw);
                    let ty = if cell.y > 40.0 { cell.y - 30.0 } else { cell.y + ch + 4.0 };
                    frame.fill(&Path::rounded_rectangle(Point::new(tx, ty), Size::new(tw, 24.0), 6.0.into()), t.text);
                    frame.fill_text(label(tip, Point::new(tx + tw / 2.0, ty + 12.0), t.surface, alignment::Horizontal::Center));
                }
            }
        }
        vec![frame.into_geometry()]
    }
}

/// A tiny trend line for table rows.
pub struct Sparkline(pub Vec<i64>);

impl<M> canvas::Program<M> for Sparkline {
    type State = ();

    fn draw(&self, _: &(), renderer: &Renderer, theme: &Theme, bounds: Rectangle, _: mouse::Cursor) -> Vec<Geometry> {
        let t = tokens(theme);
        let mut frame = Frame::new(renderer, bounds.size());
        if self.0.len() >= 2 {
            let min = *self.0.iter().min().unwrap() as f32;
            let max = *self.0.iter().max().unwrap() as f32;
            let span = (max - min).max(1.0);
            let up = self.0.last() >= self.0.first();
            let color = if up { t.success } else { t.danger };
            let path = Path::new(|b| {
                for (i, v) in self.0.iter().enumerate() {
                    let p = Point::new(bounds.width * i as f32 / (self.0.len() - 1) as f32, 2.0 + (bounds.height - 4.0) * (1.0 - (*v as f32 - min) / span));
                    if i == 0 { b.move_to(p) } else { b.line_to(p) }
                }
            });
            frame.stroke(&path, Stroke::default().with_color(color).with_width(1.8));
        }
        vec![frame.into_geometry()]
    }
}
