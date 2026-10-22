//! Small building blocks every page uses: text in the app's fonts, Lucide icons, buttons, cards and
//! direction-aware rows (Arabic and Persian lay out right to left).
use crate::theme::{self, tokens};
use iced::widget::{Space, button, column, container, row, scrollable, text, tooltip};
use iced::{Alignment, Element, Font, Length, Padding, font};

pub const INTER: Font = Font::with_name("Inter");
pub const INTER_SEMIBOLD: Font = Font { weight: font::Weight::Semibold, ..Font::with_name("Inter") };
pub const INTER_BOLD: Font = Font { weight: font::Weight::Bold, ..Font::with_name("Inter") };
pub const ICONS: Font = Font::with_name("lucide");

/// Lucide glyphs used in the app (codepoints from lucide-static's font).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Icon {
    Dashboard,
    FileText,
    Calendar,
    Chart,
    Messages,
    Telescope,
    Layers,
    Settings,
    Sparkles,
    Send,
    Plus,
    Search,
    Trash,
    Refresh,
    Eye,
    Message,
    Users,
    Check,
    X,
    Clock,
    Wand,
    Languages,
    Sun,
    Moon,
    Copy,
    External,
    Loader,
    Alert,
    Verified,
    Radio,
    Download,
    Zap,
    Flame,
    Rocket,
    Pause,
    Play,
    ChevronRight,
    ChevronDown,
    ChevronLeft,
    Hash,
    Type,
    ListChecks,
    Server,
    Key,
    CheckCircle,
    ArrowLeft,
    Image,
    Video,
    Forward,
    PenLine,
    CalendarPlus,
}

impl Icon {
    pub fn char(self) -> char {
        let cp: u32 = match self {
            Icon::Dashboard => 57793,
            Icon::FileText => 57548,
            Icon::Calendar => 58041,
            Icon::Chart => 58892,
            Icon::Messages => 58381,
            Icon::Telescope => 58821,
            Icon::Layers => 58665,
            Icon::Settings => 57925,
            Icon::Sparkles => 58386,
            Icon::Send => 57682,
            Icon::Plus => 57661,
            Icon::Search => 57681,
            Icon::Trash => 57742,
            Icon::Refresh => 57669,
            Icon::Eye => 57530,
            Icon::Message => 57622,
            Icon::Users => 57764,
            Icon::Check => 57452,
            Icon::X => 57778,
            Icon::Clock => 57936,
            Icon::Wand => 58199,
            Icon::Languages => 57598,
            Icon::Sun => 57720,
            Icon::Moon => 57630,
            Icon::Copy => 57502,
            Icon::External => 57529,
            Icon::Loader => 57610,
            Icon::Alert => 57463,
            Icon::Verified => 57921,
            Icon::Radio => 57666,
            Icon::Download => 57522,
            Icon::Zap => 57780,
            Icon::Flame => 57554,
            Icon::Rocket => 57990,
            Icon::Pause => 57646,
            Icon::Play => 57660,
            Icon::ChevronRight => 57455,
            Icon::ChevronDown => 57453,
            Icon::ChevronLeft => 57454,
            Icon::Hash => 57583,
            Icon::Type => 57752,
            Icon::ListChecks => 57808,
            Icon::Server => 57683,
            Icon::Key => 58531,
            Icon::CheckCircle => 57468,
            Icon::ArrowLeft => 57416,
            Icon::Image => 57590,
            Icon::Video => 57765,
            Icon::Forward => 57897,
            Icon::PenLine => 57648,
            Icon::CalendarPlus => 58044,
        };
        char::from_u32(cp).unwrap_or('?')
    }
}

pub fn icon<'a, M: 'a>(i: Icon, size: u16) -> Element<'a, M> {
    text(i.char().to_string()).font(ICONS).size(size as f32).line_height(1.0).into()
}

pub fn icon_tinted<'a, M: 'a>(i: Icon, size: u16, tone: &'static str) -> Element<'a, M> {
    text(i.char().to_string())
        .font(ICONS)
        .size(size as f32)
        .line_height(1.0)
        .style(move |th: &iced::Theme| text::Style { color: Some(theme::tone_colors(&tokens(th), tone).1) })
        .into()
}

pub fn body<'a>(s: impl text::IntoFragment<'a>) -> text::Text<'a> {
    text(s).font(INTER).size(14)
}

pub fn small<'a>(s: impl text::IntoFragment<'a>) -> text::Text<'a> {
    text(s).font(INTER).size(12.5).style(|th: &iced::Theme| text::Style { color: Some(tokens(th).muted) })
}

pub fn muted<'a>(s: impl text::IntoFragment<'a>) -> text::Text<'a> {
    text(s).font(INTER).size(14).style(|th: &iced::Theme| text::Style { color: Some(tokens(th).muted) })
}

pub fn faint<'a>(s: impl text::IntoFragment<'a>) -> text::Text<'a> {
    text(s).font(INTER).size(12).style(|th: &iced::Theme| text::Style { color: Some(tokens(th).faint) })
}

pub fn strong<'a>(s: impl text::IntoFragment<'a>) -> text::Text<'a> {
    text(s).font(INTER_SEMIBOLD).size(14)
}

pub fn heading<'a>(s: impl text::IntoFragment<'a>, size: u16) -> text::Text<'a> {
    text(s).font(INTER_BOLD).size(size as f32)
}

pub fn tinted<'a>(s: impl text::IntoFragment<'a>, tone: &'static str) -> text::Text<'a> {
    text(s).font(INTER_SEMIBOLD).size(13).style(move |th: &iced::Theme| text::Style { color: Some(theme::tone_colors(&tokens(th), tone).1) })
}

/// An uppercase label above a section.
pub fn eyebrow<'a>(s: String) -> text::Text<'a> {
    text(s.to_uppercase()).font(INTER_SEMIBOLD).size(11).style(|th: &iced::Theme| text::Style { color: Some(tokens(th).accent) })
}

pub fn card<'a, M: 'a>(content: impl Into<Element<'a, M>>) -> container::Container<'a, M> {
    container(content).padding(18).style(theme::card)
}

pub fn badge<'a, M: 'a>(label: String, tone: &'static str) -> Element<'a, M> {
    container(text(label).font(INTER_SEMIBOLD).size(11.5)).padding(Padding::from([3, 9])).style(theme::badge(tone)).into()
}

pub fn banner<'a, M: 'a>(i: Icon, message: String, tone: &'static str) -> Element<'a, M> {
    container(row![icon(i, 16), text(message).font(INTER).size(13.5).width(Length::Fill)].spacing(10).align_y(Alignment::Center))
        .padding(Padding::from([10, 14]))
        .style(theme::banner(tone))
        .width(Length::Fill)
        .into()
}

/// A button with an optional leading icon.
pub fn btn<'a, M: Clone + 'a>(i: Option<Icon>, label: String, style: fn(&iced::Theme, button::Status) -> button::Style, on: Option<M>) -> button::Button<'a, M> {
    let content: Element<'a, M> = match i {
        Some(i) => row![icon(i, 15), text(label).font(INTER_SEMIBOLD).size(13.5)].spacing(7).align_y(Alignment::Center).into(),
        None => text(label).font(INTER_SEMIBOLD).size(13.5).into(),
    };
    button(content).padding(Padding::from([8, 14])).style(style).on_press_maybe(on)
}

/// A square icon-only button with a tooltip.
pub fn icon_btn<'a, M: Clone + 'a>(i: Icon, tip: String, on: Option<M>) -> Element<'a, M> {
    let b = button(container(icon(i, 16)).center(18)).padding(7).style(theme::ghost).on_press_maybe(on);
    tooltip(b, container(text(tip).font(INTER).size(12)).padding(Padding::from([4, 8])).style(theme::tooltip), tooltip::Position::Bottom).into()
}

pub fn with_tip<'a, M: 'a>(content: impl Into<Element<'a, M>>, tip: String) -> Element<'a, M> {
    tooltip(content, container(text(tip).font(INTER).size(12)).padding(Padding::from([4, 8])).style(theme::tooltip), tooltip::Position::Top).into()
}

/// A round avatar with initials (channels without a photo).
pub fn initials_avatar<'a, M: 'a>(title: &str, seed: &str, size: f32) -> Element<'a, M> {
    let letters: String = title
        .split_whitespace()
        .filter_map(|w| w.chars().find(|c| c.is_alphanumeric()))
        .take(2)
        .flat_map(char::to_uppercase)
        .collect();
    let letters = if letters.is_empty() { "#".to_string() } else { letters };
    container(text(letters).font(INTER_BOLD).size(size * 0.38).line_height(1.0)).center(size).style(theme::avatar(seed)).into()
}

/// A labelled form field.
pub fn field<'a, M: 'a>(label: String, input: impl Into<Element<'a, M>>, hint: Option<String>) -> Element<'a, M> {
    let mut c = column![text(label).font(INTER_SEMIBOLD).size(13), input.into()].spacing(6);
    if let Some(h) = hint {
        c = c.push(small(h));
    }
    c.into()
}

pub fn hspace<'a, M: 'a>() -> Element<'a, M> {
    Space::new().width(Length::Fill).into()
}

pub fn gap<'a, M: 'a>(px: f32) -> Element<'a, M> {
    Space::new().width(px).height(px).into()
}

pub fn hr<'a, M: 'a>() -> Element<'a, M> {
    container(Space::new()).height(1).width(Length::Fill).style(theme::divider).into()
}

/// A row whose children run right to left in Arabic and Persian.
pub fn dir_row<'a, M: 'a>(rtl: bool, mut items: Vec<Element<'a, M>>) -> iced::widget::Row<'a, M> {
    if rtl {
        items.reverse();
    }
    iced::widget::Row::with_children(items)
}

/// Horizontal alignment for a text block in the current direction.
pub fn start(rtl: bool) -> Alignment {
    if rtl { Alignment::End } else { Alignment::Start }
}

/// A key figure with its label and a note below.
pub fn stat<'a, M: 'a>(i: Icon, tone: &'static str, label: String, value: String, note: String, rtl: bool) -> Element<'a, M> {
    let icon_box = container(icon_tinted(i, 17, tone)).center(34).style(theme::badge(tone));
    card(
        column![
            dir_row(rtl, vec![small(label).into(), hspace(), icon_box.into()]).align_y(Alignment::Center),
            container(heading(value, 26)).align_x(start(rtl)).width(Length::Fill),
            container(small(note)).align_x(start(rtl)).width(Length::Fill),
        ]
        .spacing(6),
    )
    .width(Length::Fill)
    .into()
}

/// A horizontal bar (value 0..1) used for content-lift breakdowns.
pub fn meter<'a, M: 'a>(k: f32, tone: &'static str) -> Element<'a, M> {
    let k = k.clamp(0.0, 1.0);
    let filled = (k * 1000.0).round() as u16;
    let rest = 1000u16.saturating_sub(filled);
    let bar = container(Space::new()).height(8).width(Length::FillPortion(filled.max(1))).style(move |th: &iced::Theme| {
        let c = theme::tone_colors(&tokens(th), tone).1;
        container::Style { background: Some(c.into()), border: iced::Border { radius: 99.0.into(), ..Default::default() }, ..Default::default() }
    });
    let mut r = row![bar];
    if rest > 0 {
        r = r.push(Space::new().width(Length::FillPortion(rest)));
    }
    container(r).style(|th: &iced::Theme| container::Style { background: Some(tokens(th).surface2.into()), border: iced::Border { radius: 99.0.into(), ..Default::default() }, ..Default::default() }).width(Length::Fill).into()
}

/// A vertical scroll area with the app's thin scrollbar.
pub fn scroll<'a, M: 'a>(content: impl Into<Element<'a, M>>) -> scrollable::Scrollable<'a, M> {
    scrollable(content).direction(scrollable::Direction::Vertical(scrollable::Scrollbar::new().width(6).scroller_width(6).spacing(4))).style(theme::scroll)
}
