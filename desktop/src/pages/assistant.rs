//! Assistant: a chat with the AI that knows the channel, its analytics, the queue and the watchlist.
use crate::app::{AiSlot, App, Message};
use crate::pages::ai_unready_card;
use crate::theme;
use crate::ui::*;
use crate::view::channel_avatar;
use iced::widget::{button, column, container, row, text, text_editor};
use iced::{Alignment, Element, Length, Padding};

const STARTERS: [&str; 6] = ["chat.s_ideas", "chat.s_today", "chat.s_week", "chat.s_best", "chat.s_series", "chat.s_competitors"];

pub fn view(app: &App) -> Element<'_, Message> {
    let rtl = app.rtl();
    let a = &app.assistant;
    let channel = app.focus_channel();
    let context: Element<Message> = match channel {
        Some(c) => dir_row(rtl, vec![channel_avatar(app, c, 26.0), column![strong(c.name.clone()), small(app.t("chat.context"))].spacing(1).into()]).spacing(10).align_y(Alignment::Center).into(),
        None => small(app.t("chat.no_channel")).into(),
    };
    let header = dir_row(rtl, vec![context, hspace(), btn(Some(Icon::Trash), app.t("chat.clear"), theme::ghost, (!a.history.is_empty()).then_some(Message::AssistantClear)).into()]).align_y(Alignment::Center);

    let mut messages = column![].spacing(14).padding(Padding::from([4, 8]));
    if a.history.is_empty() && !a.ai.running {
        let starters: [Element<Message>; 6] = STARTERS.map(|k| button(text(app.t(k)).font(INTER).size(13.5)).padding(Padding::from([10, 14])).width(Length::Fill).style(theme::tile).on_press(Message::AssistantPrompt(app.t(k))).into());
        let [a1, a2, a3, a4, a5, a6] = starters;
        messages = messages.push(container(
            column![
                container(icon_tinted(Icon::Sparkles, 24, "accent")).center(56).style(theme::badge("accent")),
                heading(app.t("chat.hello"), 22),
                muted(app.t("chat.hello_text")),
                gap(6.0),
                row![a1, a2].spacing(10),
                row![a3, a4].spacing(10),
                row![a5, a6].spacing(10),
            ]
            .spacing(10)
            .align_x(Alignment::Center)
            .max_width(640),
        ).center_x(Length::Fill).padding(Padding::from([30, 0])));
        messages = messages.push(gap(0.0));
    }
    for (k, m) in a.history.iter().enumerate() {
        messages = messages.push(bubble(app, k, &m.role, &m.content));
    }
    if a.ai.running {
        let content = if a.ai.output.is_empty() { "…".to_string() } else { a.ai.output.clone() };
        messages = messages.push(bubble(app, usize::MAX, "assistant", &content));
    }
    if let Some(e) = &a.ai.error {
        messages = messages.push(banner(Icon::Alert, e.clone(), "danger"));
    }
    let wall = container(scroll(container(messages).center_x(Length::Fill)).anchor_bottom().height(Length::Fill)).padding(10).style(theme::chat_wall).height(Length::Fill).width(Length::Fill);

    let input = text_editor(&a.input).placeholder(app.t("chat.placeholder")).on_action(Message::AssistantInput).padding(12).font(INTER).size(14).height(86).style(theme::editor);
    let send: Element<Message> = if a.ai.running {
        btn(Some(Icon::X), app.t("ai.stop"), theme::secondary, Some(Message::AiStop(AiSlot::Assistant))).into()
    } else {
        btn(Some(Icon::Send), app.t("chat.send"), theme::primary, Some(Message::AssistantSend)).into()
    };
    let composer = dir_row(rtl, vec![container(input).width(Length::Fill).into(), container(send).align_y(Alignment::End).height(86).into()]).spacing(10);

    let mut col = column![header].spacing(12).height(Length::Fill);
    if !app.ai_ready() {
        col = col.push(ai_unready_card(app));
    }
    card(col.push(wall).push(composer)).height(Length::Fill).width(Length::Fill).into()
}

fn bubble<'a>(app: &'a App, k: usize, role: &str, content: &str) -> Element<'a, Message> {
    let rtl = app.rtl();
    let user = role == "user";
    let body = text(content.to_string()).font(INTER).size(14).line_height(1.5);
    let mut col = column![body].spacing(8);
    if !user && k != usize::MAX {
        col = col.push(
            dir_row(
                rtl,
                vec![
                    button(row![icon(Icon::Copy, 12), small(app.t("ai.copy"))].spacing(5).align_y(Alignment::Center)).padding(Padding::from([3, 8])).style(theme::ghost).on_press(Message::Copy(content.to_string())).into(),
                    button(row![icon(Icon::PenLine, 12), small(app.t("chat.to_studio"))].spacing(5).align_y(Alignment::Center)).padding(Padding::from([3, 8])).style(theme::ghost).on_press(Message::AssistantToStudio(k)).into(),
                ],
            )
            .spacing(4),
        );
    }
    let b = container(col).padding(Padding::from([10, 14])).max_width(720).style(if user { theme::user_bubble } else { theme::bubble });
    // The user's messages sit at the end of the line, the assistant's at the start (mirrored in RTL).
    let end = user != rtl;
    container(b).width(Length::Fill).align_x(if end { Alignment::End } else { Alignment::Start }).into()
}
