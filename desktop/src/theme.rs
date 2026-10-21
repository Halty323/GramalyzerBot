//! Colors and widget styles. Two palettes (light, dark) of design tokens; every style function picks the
//! palette from the theme it is handed, so switching theme restyles everything.
use iced::widget::{button, container, text_editor, text_input};
use iced::{Background, Border, Color, Shadow, Theme, Vector, color};

#[derive(Debug, Clone, Copy)]
pub struct Tokens {
    pub bg: Color,
    pub surface: Color,
    pub surface2: Color,
    pub sidebar: Color,
    pub border: Color,
    pub text: Color,
    pub muted: Color,
    pub faint: Color,
    pub accent: Color,
    pub accent_soft: Color,
    pub accent_text: Color,
    pub success: Color,
    pub success_soft: Color,
    pub warning: Color,
    pub warning_soft: Color,
    pub danger: Color,
    pub danger_soft: Color,
    pub bubble: Color,
    pub shadow: Color,
}

pub const LIGHT: Tokens = Tokens {
    bg: color!(0xf5f6f8),
    surface: color!(0xffffff),
    surface2: color!(0xf0f2f5),
    sidebar: color!(0xfbfbfc),
    border: color!(0xe2e5ea),
    text: color!(0x0f1729),
    muted: color!(0x5f6b7e),
    faint: color!(0x97a0b0),
    accent: color!(0x5b54e8),
    accent_soft: color!(0xeeedfd),
    accent_text: color!(0xffffff),
    success: color!(0x15803d),
    success_soft: color!(0xe7f6ec),
    warning: color!(0xb45309),
    warning_soft: color!(0xfdf3e3),
    danger: color!(0xc81e1e),
    danger_soft: color!(0xfdecec),
    bubble: color!(0xffffff),
    shadow: Color { r: 0.06, g: 0.09, b: 0.16, a: 0.07 },
};

pub const DARK: Tokens = Tokens {
    bg: color!(0x0d0f14),
    surface: color!(0x151821),
    surface2: color!(0x1b1f2a),
    sidebar: color!(0x11131a),
    border: color!(0x262b37),
    text: color!(0xe7e9ef),
    muted: color!(0x939bad),
    faint: color!(0x5f677a),
    accent: color!(0x8b84ff),
    accent_soft: color!(0x24234a),
    accent_text: color!(0x0d0f14),
    success: color!(0x4ade80),
    success_soft: color!(0x13291c),
    warning: color!(0xfbbf24),
    warning_soft: color!(0x2d2412),
    danger: color!(0xf87171),
    danger_soft: color!(0x311717),
    bubble: color!(0x1e2330),
    shadow: Color { r: 0.0, g: 0.0, b: 0.0, a: 0.35 },
};

pub fn theme(dark: bool) -> Theme {
    let t = if dark { DARK } else { LIGHT };
    Theme::custom(
        if dark { "Gramalyzer Dark" } else { "Gramalyzer Light" },
        iced::theme::Palette { background: t.bg, text: t.text, primary: t.accent, success: t.success, warning: t.warning, danger: t.danger },
    )
}

pub fn tokens(theme: &Theme) -> Tokens {
    if theme.extended_palette().is_dark { DARK } else { LIGHT }
}

pub const RADIUS: f32 = 10.0;

fn border(color: Color, radius: f32) -> Border {
    Border { color, width: 1.0, radius: radius.into() }
}

// ---- containers ----

pub fn page(theme: &Theme) -> container::Style {
    let t = tokens(theme);
    container::Style { background: Some(t.bg.into()), text_color: Some(t.text), ..Default::default() }
}

pub fn sidebar(theme: &Theme) -> container::Style {
    let t = tokens(theme);
    container::Style { background: Some(t.sidebar.into()), border: Border { color: t.border, width: 1.0, radius: 0.0.into() }, text_color: Some(t.text), ..Default::default() }
}

pub fn card(theme: &Theme) -> container::Style {
    let t = tokens(theme);
    container::Style {
        background: Some(t.surface.into()),
        border: border(t.border, 14.0),
        shadow: Shadow::default(),
        text_color: Some(t.text),
        snap: true,
    }
}

pub fn inset(theme: &Theme) -> container::Style {
    let t = tokens(theme);
    container::Style { background: Some(t.surface2.into()), border: border(t.border, RADIUS), ..Default::default() }
}

pub fn bubble(theme: &Theme) -> container::Style {
    let t = tokens(theme);
    container::Style {
        background: Some(t.bubble.into()),
        border: Border { color: t.border, width: 1.0, radius: iced::border::Radius { top_left: 14.0, top_right: 14.0, bottom_right: 14.0, bottom_left: 4.0 } },
        // No shadow: the software renderer draws shadows past a scrollable's clip.
        shadow: Shadow::default(),
        text_color: Some(t.text),
        snap: true,
    }
}

pub fn user_bubble(theme: &Theme) -> container::Style {
    let t = tokens(theme);
    container::Style {
        background: Some(t.accent_soft.into()),
        border: Border { color: Color::TRANSPARENT, width: 0.0, radius: iced::border::Radius { top_left: 14.0, top_right: 14.0, bottom_right: 4.0, bottom_left: 14.0 } },
        text_color: Some(t.text),
        ..Default::default()
    }
}

pub fn chat_wall(theme: &Theme) -> container::Style {
    let t = tokens(theme);
    let wall = if theme.extended_palette().is_dark { color!(0x0f1219) } else { color!(0xe9edf2) };
    container::Style { background: Some(wall.into()), border: border(t.border, 14.0), ..Default::default() }
}

/// A small rounded label: tone is "accent", "success", "warning", "danger" or "muted".
pub fn badge(tone: &'static str) -> impl Fn(&Theme) -> container::Style {
    move |theme| {
        let t = tokens(theme);
        let (bg, fg) = tone_colors(&t, tone);
        container::Style { background: Some(bg.into()), text_color: Some(fg), border: border(Color::TRANSPARENT, 99.0), ..Default::default() }
    }
}

pub fn tone_colors(t: &Tokens, tone: &str) -> (Color, Color) {
    match tone {
        "accent" => (t.accent_soft, t.accent),
        "success" => (t.success_soft, t.success),
        "warning" => (t.warning_soft, t.warning),
        "danger" => (t.danger_soft, t.danger),
        _ => (t.surface2, t.muted),
    }
}

pub fn banner(tone: &'static str) -> impl Fn(&Theme) -> container::Style {
    move |theme| {
        let t = tokens(theme);
        let (bg, fg) = tone_colors(&t, tone);
        container::Style { background: Some(bg.into()), text_color: Some(fg), border: border(Color { a: 0.25, ..fg }, RADIUS), ..Default::default() }
    }
}

pub fn avatar(seed: &str) -> impl Fn(&Theme) -> container::Style + use<> {
    let c = avatar_color(seed);
    move |_| container::Style { background: Some(c.into()), text_color: Some(Color::WHITE), border: border(Color::TRANSPARENT, 99.0), ..Default::default() }
}

pub fn avatar_color(seed: &str) -> Color {
    const COLORS: [Color; 8] = [color!(0x6d5ef0), color!(0x0ea5e9), color!(0x10b981), color!(0xf59e0b), color!(0xef4444), color!(0xec4899), color!(0x14b8a6), color!(0x8b5cf6)];
    let h = seed.bytes().fold(7u32, |h, b| h.wrapping_mul(31).wrapping_add(b as u32));
    COLORS[(h % COLORS.len() as u32) as usize]
}

pub fn divider(theme: &Theme) -> container::Style {
    container::Style { background: Some(tokens(theme).border.into()), ..Default::default() }
}

pub fn tooltip(theme: &Theme) -> container::Style {
    let t = tokens(theme);
    container::Style { background: Some(t.text.into()), text_color: Some(t.surface), border: border(Color::TRANSPARENT, 6.0), ..Default::default() }
}

// ---- buttons ----

fn base_button(bg: Option<Color>, fg: Color, border_color: Color) -> button::Style {
    button::Style { background: bg.map(Background::Color), text_color: fg, border: border(border_color, RADIUS), shadow: Shadow::default(), snap: true }
}

pub fn primary(theme: &Theme, status: button::Status) -> button::Style {
    let t = tokens(theme);
    match status {
        button::Status::Disabled => base_button(Some(Color { a: 0.45, ..t.accent }), t.accent_text, Color::TRANSPARENT),
        button::Status::Hovered | button::Status::Pressed => {
            base_button(Some(mix(t.accent, t.text, 0.12)), t.accent_text, Color::TRANSPARENT)
        }
        _ => base_button(Some(t.accent), t.accent_text, Color::TRANSPARENT),
    }
}

pub fn secondary(theme: &Theme, status: button::Status) -> button::Style {
    let t = tokens(theme);
    match status {
        button::Status::Disabled => base_button(Some(t.surface), t.faint, t.border),
        button::Status::Hovered | button::Status::Pressed => base_button(Some(t.surface2), t.text, mix(t.border, t.text, 0.15)),
        _ => base_button(Some(t.surface), t.text, t.border),
    }
}

pub fn soft(theme: &Theme, status: button::Status) -> button::Style {
    let t = tokens(theme);
    match status {
        button::Status::Disabled => base_button(Some(t.surface2), t.faint, Color::TRANSPARENT),
        button::Status::Hovered | button::Status::Pressed => base_button(Some(mix(t.accent_soft, t.accent, 0.12)), t.accent, Color::TRANSPARENT),
        _ => base_button(Some(t.accent_soft), t.accent, Color::TRANSPARENT),
    }
}

pub fn ghost(theme: &Theme, status: button::Status) -> button::Style {
    let t = tokens(theme);
    match status {
        button::Status::Disabled => base_button(None, t.faint, Color::TRANSPARENT),
        button::Status::Hovered | button::Status::Pressed => base_button(Some(t.surface2), t.text, Color::TRANSPARENT),
        _ => base_button(None, t.muted, Color::TRANSPARENT),
    }
}

pub fn danger(theme: &Theme, status: button::Status) -> button::Style {
    let t = tokens(theme);
    match status {
        button::Status::Hovered | button::Status::Pressed => base_button(Some(t.danger), Color::WHITE, Color::TRANSPARENT),
        _ => base_button(Some(t.danger_soft), t.danger, Color::TRANSPARENT),
    }
}

pub fn nav(active: bool) -> impl Fn(&Theme, button::Status) -> button::Style {
    move |theme, status| {
        let t = tokens(theme);
        if active {
            return base_button(Some(t.accent_soft), t.accent, Color::TRANSPARENT);
        }
        match status {
            button::Status::Hovered | button::Status::Pressed => base_button(Some(t.surface2), t.text, Color::TRANSPARENT),
            _ => base_button(None, t.muted, Color::TRANSPARENT),
        }
    }
}

/// A row in a list that highlights when selected.
pub fn list_item(selected: bool) -> impl Fn(&Theme, button::Status) -> button::Style {
    move |theme, status| {
        let t = tokens(theme);
        let mut s = match (selected, status) {
            (true, _) => base_button(Some(t.accent_soft), t.text, Color { a: 0.35, ..t.accent }),
            (false, button::Status::Hovered | button::Status::Pressed) => base_button(Some(t.surface2), t.text, Color::TRANSPARENT),
            _ => base_button(None, t.text, Color::TRANSPARENT),
        };
        s.border.radius = 10.0.into();
        s
    }
}

/// A segmented-control / filter chip.
pub fn chip(selected: bool) -> impl Fn(&Theme, button::Status) -> button::Style {
    move |theme, status| {
        let t = tokens(theme);
        let mut s = match (selected, status) {
            (true, _) => base_button(Some(t.text), t.surface, Color::TRANSPARENT),
            (false, button::Status::Hovered | button::Status::Pressed) => base_button(Some(t.surface2), t.text, t.border),
            _ => base_button(Some(t.surface), t.muted, t.border),
        };
        s.border.radius = 99.0.into();
        s
    }
}

pub fn link(theme: &Theme, status: button::Status) -> button::Style {
    let t = tokens(theme);
    let fg = match status {
        button::Status::Hovered | button::Status::Pressed => mix(t.accent, t.text, 0.2),
        _ => t.accent,
    };
    base_button(None, fg, Color::TRANSPARENT)
}

/// A calendar cell or card that is clickable.
pub fn tile(theme: &Theme, status: button::Status) -> button::Style {
    let t = tokens(theme);
    let mut s = match status {
        button::Status::Hovered | button::Status::Pressed => base_button(Some(t.surface), t.text, Color { a: 0.5, ..t.accent }),
        _ => base_button(Some(t.surface), t.text, t.border),
    };
    s.border.radius = 8.0.into();
    s
}

// ---- inputs ----

pub fn input(theme: &Theme, status: text_input::Status) -> text_input::Style {
    let t = tokens(theme);
    let border_color = match status {
        text_input::Status::Focused { .. } => t.accent,
        text_input::Status::Hovered => mix(t.border, t.text, 0.2),
        _ => t.border,
    };
    text_input::Style {
        background: (if matches!(status, text_input::Status::Disabled) { t.surface2 } else { t.surface }).into(),
        border: border(border_color, RADIUS),
        icon: t.muted,
        placeholder: t.faint,
        value: t.text,
        selection: Color { a: 0.3, ..t.accent },
    }
}

pub fn editor(theme: &Theme, status: text_editor::Status) -> text_editor::Style {
    let t = tokens(theme);
    let border_color = match status {
        text_editor::Status::Focused { .. } => t.accent,
        text_editor::Status::Hovered => mix(t.border, t.text, 0.2),
        _ => t.border,
    };
    text_editor::Style {
        background: (if matches!(status, text_editor::Status::Disabled) { t.surface2 } else { t.surface }).into(),
        border: border(border_color, RADIUS),
        placeholder: t.faint,
        value: t.text,
        selection: Color { a: 0.3, ..t.accent },
    }
}

pub fn mix(a: Color, b: Color, k: f32) -> Color {
    Color { r: a.r + (b.r - a.r) * k, g: a.g + (b.g - a.g) * k, b: a.b + (b.b - a.b) * k, a: a.a + (b.a - a.a) * k }
}

// ---- scrollbars and menus ----

pub fn scroll(theme: &Theme, status: iced::widget::scrollable::Status) -> iced::widget::scrollable::Style {
    use iced::widget::scrollable::{AutoScroll, Rail, Scroller, Status, Style};
    let t = tokens(theme);
    let active = match status {
        Status::Hovered { is_vertical_scrollbar_hovered: true, .. } | Status::Hovered { is_horizontal_scrollbar_hovered: true, .. } => mix(t.border, t.text, 0.35),
        Status::Dragged { .. } => mix(t.border, t.text, 0.45),
        _ => t.border,
    };
    let rail = Rail { background: None, border: Border::default(), scroller: Scroller { background: active.into(), border: border(Color::TRANSPARENT, 99.0) } };
    Style {
        container: container::Style::default(),
        vertical_rail: rail,
        horizontal_rail: rail,
        gap: None,
        auto_scroll: AutoScroll { background: t.surface.into(), border: border(t.border, 99.0), shadow: Shadow::default(), icon: t.muted },
    }
}

pub fn pick(theme: &Theme, status: iced::widget::pick_list::Status) -> iced::widget::pick_list::Style {
    use iced::widget::pick_list::Status;
    let t = tokens(theme);
    let border_color = match status {
        Status::Opened { .. } => t.accent,
        Status::Hovered => mix(t.border, t.text, 0.2),
        Status::Active => t.border,
    };
    iced::widget::pick_list::Style { text_color: t.text, placeholder_color: t.faint, handle_color: t.muted, background: t.surface.into(), border: border(border_color, RADIUS) }
}

pub fn menu(theme: &Theme) -> iced::overlay::menu::Style {
    let t = tokens(theme);
    iced::overlay::menu::Style {
        background: t.surface.into(),
        border: border(t.border, RADIUS),
        text_color: t.text,
        selected_text_color: t.accent,
        selected_background: t.accent_soft.into(),
        shadow: Shadow { color: t.shadow, offset: Vector::new(0.0, 4.0), blur_radius: 16.0 },
    }
}
