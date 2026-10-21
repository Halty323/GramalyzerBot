//! Channels: every connected channel as a card, with its best time and quick actions.
use crate::app::{App, Message, Source};
use crate::model::PostStatus;
use crate::theme;
use crate::ui::*;
use crate::view::channel_avatar;
use iced::widget::{column, container};
use iced::{Alignment, Element, Length};

pub fn view(app: &App) -> Element<'_, Message> {
    let i = &app.i18n;
    let rtl = app.rtl();
    let demo = app.demo();
    let cards: Vec<Element<Message>> = app
        .channels()
        .iter()
        .filter(|c| app.channel.as_ref().is_none_or(|id| &c.id == id))
        .map(|c| {
            let queued = app.posts().iter().filter(|p| p.channel_id == c.id && p.status == PostStatus::Scheduled).count() as i64;
            let best: (String, String) = match app.insights.get(&c.id) {
                Some(Ok(ins)) if ins.status == "ready" && !ins.best_times.is_empty() => {
                    let b = &ins.best_times[0];
                    let day = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"].iter().position(|d| *d == b.day).unwrap_or(0);
                    (format!("{} {:02}:00", i.weekday_short(day), b.hour), i.tv("chan.best_lift", &[("lift", &format!("{}×", i.dec(b.lift, 1)))]))
                }
                Some(Ok(ins)) if ins.status == "learning" => (app.t("chan.learning"), i.tn("chan.more_posts", ins.posts_needed)),
                Some(_) => ("—".into(), app.t("chan.not_collecting")),
                None => ("…".into(), app.t("chan.best_time")),
            };
            let status = if c.paused { badge(app.t("chan.paused"), "warning") } else { badge(app.t("chan.active"), "success") };
            let figure = |value: String, label: String| column![heading(value, 17), small(label)].spacing(2).width(Length::Fill);
            let handle: Element<Message> = if c.handle.is_empty() {
                small(app.t("chan.private")).into()
            } else {
                iced::widget::button(small(c.handle.clone())).padding(0).style(theme::link).on_press(Message::OpenUrl(format!("https://t.me/{}", c.handle.trim_start_matches('@')))).into()
            };
            card(
                column![
                    dir_row(rtl, vec![channel_avatar(app, c, 48.0), hspace(), status]).align_y(Alignment::Center),
                    column![heading(c.name.clone(), 17), handle].spacing(2),
                    small(c.description.clone()),
                    hr(),
                    dir_row(
                        rtl,
                        vec![
                            figure(i.num(c.subscribers), app.t("chan.subscribers")).into(),
                            if demo { figure(i.percent(c.engagement, 1), app.t("chan.sample_engagement")).into() } else { figure(best.0, best.1).into() },
                            figure(i.num(queued), app.t("chan.queued")).into(),
                        ]
                    )
                    .spacing(10),
                    dir_row(
                        rtl,
                        vec![
                            btn(Some(Icon::Settings), app.t("chan.configure"), theme::secondary, Some(Message::Configure(c.id.clone()))).into(),
                            icon_btn(Icon::Messages, i.tv("chan.view", &[("name", &c.name)]), Some(Message::View(Source::Mine(c.id.clone())))),
                            hspace(),
                            btn(Some(if c.paused { Icon::Play } else { Icon::Pause }), app.t(if c.paused { "chan.resume" } else { "chan.pause" }), theme::ghost, Some(Message::TogglePause(c.id.clone()))).into(),
                        ]
                    )
                    .spacing(6)
                    .align_y(Alignment::Center),
                ]
                .spacing(12),
            )
            .width(Length::Fill)
            .into()
        })
        .collect();
    let mut grid = column![].spacing(14);
    let mut it = cards.into_iter().peekable();
    while it.peek().is_some() {
        let mut chunk: Vec<Element<Message>> = it.by_ref().take(3).collect();
        while chunk.len() < 3 {
            chunk.push(container(gap(0.0)).width(Length::Fill).into());
        }
        grid = grid.push(dir_row(rtl, chunk).spacing(14));
    }
    grid.into()
}
