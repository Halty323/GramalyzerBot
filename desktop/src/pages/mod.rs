//! One module per page, plus the pieces several pages share.
pub mod assistant;
pub mod channels;
pub mod dashboard;
pub mod insights;
pub mod planner;
pub mod settings;
pub mod studio;
pub mod viewer;
pub mod watchlist;

use crate::app::{AiRun, AiSlot, App, Message, SettingsTab, local, status_key};
use crate::model::{Post, PostStatus};
use crate::theme;
use crate::ui::*;
use crate::view::channel_avatar;
use iced::widget::{button, column, container, text};
use iced::{Alignment, Element, Length, Padding};

/// A card's heading with an optional line of explanation.
pub fn section<'a>(app: &App, title: String, subtitle: Option<String>) -> Element<'a, Message> {
    let mut c = column![heading(title, 16)].spacing(3);
    if let Some(s) = subtitle {
        c = c.push(small(s));
    }
    container(c).align_x(start(app.rtl())).into()
}

pub fn status_badge<'a>(app: &App, p: &Post) -> Element<'a, Message> {
    let tone = match (p.status, p.delivery_state.as_deref()) {
        (_, Some("failed")) => "danger",
        (_, Some("uncertain")) => "warning",
        (PostStatus::Published, _) => "success",
        (PostStatus::Scheduled, _) => "accent",
        (PostStatus::Draft, _) => "muted",
    };
    let label = match p.delivery_state.as_deref() {
        Some("failed") => app.t("status.failed"),
        Some("uncertain") => app.t("status.uncertain"),
        Some("sending") => app.t("status.sending"),
        _ => app.t(&format!("status.{}", status_key(p.status))),
    };
    badge(label, tone)
}

/// A post in a list: channel avatar, title, when, status. Opens it in the Studio.
pub fn post_row<'a>(app: &'a App, p: &'a Post) -> Element<'a, Message> {
    let i = &app.i18n;
    let rtl = app.rtl();
    let channel = app.channel_by_id(&p.channel_id);
    let mut meta = local(&p.scheduled_at).map(|d| i.when(&d)).unwrap_or_default();
    if app.demo() && p.status == PostStatus::Published {
        meta = format!("{meta} · {} {}", i.compact(p.views as f64), app.t("studio.views"));
    }
    let avatar = channel.map(|c| channel_avatar(app, c, 30.0)).unwrap_or_else(|| initials_avatar(&p.title, &p.id, 30.0));
    let title = container(strong(clip(&p.title, 60)).size(13.5)).width(Length::Fill).align_x(start(rtl));
    let meta = dir_row(rtl, vec![container(small(meta)).width(Length::Fill).align_x(start(rtl)).into(), status_badge(app, p)]).spacing(8).align_y(Alignment::Center);
    let text_col = column![title, meta].spacing(4).width(Length::Fill);
    button(dir_row(rtl, vec![avatar, text_col.into()]).spacing(10).align_y(Alignment::Center))
        .width(Length::Fill)
        .padding(Padding::from([7, 8]))
        .style(theme::list_item(app.studio.open && app.studio.editing.as_deref() == Some(&p.id)))
        .on_press(Message::OpenPost(p.id.clone()))
        .into()
}

pub fn ai_unready_card<'a>(app: &App) -> Element<'a, Message> {
    card(
        dir_row(
            app.rtl(),
            vec![
                container(icon_tinted(Icon::Sparkles, 20, "accent")).center(44).style(theme::badge("accent")).into(),
                column![strong(app.t("ai.unready_title")), small(app.t("ai.unready_text"))].spacing(3).width(Length::Fill).into(),
                btn(Some(Icon::Key), app.t("ai.connect"), theme::primary, Some(Message::SettingsTab(SettingsTab::Ai))).into(),
            ],
        )
        .spacing(14)
        .align_y(Alignment::Center),
    )
    .width(Length::Fill)
    .into()
}

/// The streamed answer of an AI request: the text so far, a stop button while it runs, copy when done.
pub fn ai_output<'a>(app: &'a App, run: &'a AiRun, slot: AiSlot, max_height: f32) -> Option<Element<'a, Message>> {
    if !run.running && run.output.is_empty() && run.error.is_none() {
        return None;
    }
    let rtl = app.rtl();
    let mut col = column![].spacing(10);
    let status: Element<Message> = if run.running {
        dir_row(rtl, vec![icon_tinted(Icon::Loader, 14, "accent"), container(small(app.t("ai.writing"))).width(Length::Fill).align_x(start(rtl)).into(), btn(Some(Icon::X), app.t("ai.stop"), theme::ghost, Some(Message::AiStop(slot))).into()]).spacing(8).align_y(Alignment::Center).into()
    } else {
        // The provider name takes the leftover width (and wraps) so the copy button never gets pushed out.
        dir_row(rtl, vec![icon_tinted(Icon::Sparkles, 14, "accent"), container(small(crate::ai::preset(&app.config.ai.provider).name.to_string())).width(Length::Fill).align_x(start(rtl)).into(), btn(Some(Icon::Copy), app.t("ai.copy"), theme::ghost, (!run.output.is_empty()).then(|| Message::Copy(run.output.clone()))).into()]).spacing(8).align_y(Alignment::Center).into()
    };
    col = col.push(status);
    if !run.output.is_empty() {
        let t = text(run.output.clone()).font(INTER).size(14).line_height(1.5).width(Length::Fill).align_x(if rtl { iced::alignment::Horizontal::Right } else { iced::alignment::Horizontal::Left });
        col = col.push(container(scroll(container(t).padding(Padding::from([0, 6]))).anchor_bottom()).max_height(max_height));
    }
    if let Some(e) = &run.error {
        col = col.push(banner(Icon::Alert, e.clone(), "danger"));
    }
    Some(container(col).padding(14).style(theme::inset).width(Length::Fill).into())
}

/// Shortens text to n characters with an ellipsis.
pub fn clip(s: &str, n: usize) -> String {
    let one_line = s.replace('\n', " ");
    if one_line.chars().count() <= n { one_line } else { format!("{}…", one_line.chars().take(n).collect::<String>().trim_end()) }
}
