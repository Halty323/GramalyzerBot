//! Insights: when and what to post, from the bot's analysis of the channel, with an AI review on top.
use crate::app::{AiSlot, App, Message, local};
use crate::charts::Heatmap;
use crate::model::{InsightGroup, Insights};
use crate::pages::{ai_output, clip, section};
use crate::theme;
use crate::ui::*;
use iced::widget::{canvas, column, container, row};
use iced::{Alignment, Element, Length};

const DAYS: [&str; 7] = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"];

fn day_index(day: &str) -> usize {
    DAYS.iter().position(|d| *d == day).unwrap_or(0)
}

pub fn view(app: &App) -> Element<'_, Message> {
    let i = &app.i18n;
    let rtl = app.rtl();
    let Some(channel) = app.focus_channel() else {
        return card(muted(app.t("studio.no_channels"))).width(Length::Fill).into();
    };
    let mut page = column![].spacing(16);
    if app.channel.is_none() && app.channels().len() > 1 {
        page = page.push(small(i.tv("ins.scope", &[("name", &channel.name)])));
    }
    let data = match app.insights.get(&channel.id) {
        None => return card(row![icon(Icon::Loader, 16), muted(i.tv("ins.reading", &[("name", &channel.name)]))].spacing(10)).width(Length::Fill).into(),
        Some(Err(e)) => return banner(Icon::Alert, app.api_error(e), "danger"),
        Some(Ok(d)) => d,
    };
    if data.status == "not_connected" {
        return page.push(banner(Icon::Radio, format!("{} {}", i.tv("ins.nc_title", &[("name", &channel.name)]), app.t("ins.nc_text")), "accent")).into();
    }
    if data.status == "learning" {
        let done = data.counts.eligible as f32 / (data.counts.eligible + data.posts_needed).max(1) as f32;
        return page
            .push(card(column![eyebrow(app.t("ins.learning")), heading(i.tn("ins.learning_title", data.posts_needed), 20), small(i.tn("ins.usable", data.counts.eligible)), meter(done, "accent")].spacing(10)).width(Length::Fill))
            .into();
    }

    let head = dir_row(
        rtl,
        vec![
            column![eyebrow(app.t("ins.eyebrow")), heading(channel.name.clone(), 22), small(format!("{} · {} · {}", app.t(&format!("conf.{}", data.confidence)), if data.window_days > 0 { i.tn("ins.window", data.window_days) } else { app.t("ins.all_time") }, i.tn("ins.posts", data.counts.eligible)))].spacing(4).into(),
            hspace(),
            btn(Some(Icon::Sparkles), app.t("ins.ai_review"), theme::soft, (!app.insights_ai.running).then_some(Message::InsightsAi)).into(),
        ],
    )
    .align_y(Alignment::End);
    page = page.push(head);
    if let Some(out) = ai_output(app, &app.insights_ai, AiSlot::Insights, 420.0) {
        page = page.push(out);
    }

    let best = card(
        column![
            section(app, app.t("ins.best_times"), Some(i.tv("ins.best_times_text", &[("zone", &data.timezone)]))),
            column(data.best_times.iter().take(5).enumerate().map(|(k, s)| {
                dir_row(
                    rtl,
                    vec![
                        container(strong((k + 1).to_string()).size(12)).center(24).style(theme::badge(if k == 0 { "accent" } else { "muted" })).into(),
                        strong(format!("{} {:02}:00–{:02}:00", i.weekday(day_index(&s.day)), s.hour, (s.hour + 1) % 24)).into(),
                        hspace(),
                        tinted(format!("{}×", i.dec(s.lift, 1)), "accent").into(),
                        small(i.tn("ins.nearby", s.support)).into(),
                    ],
                )
                .spacing(10)
                .align_y(Alignment::Center)
                .into()
            }))
            .spacing(8),
        ]
        .spacing(12),
    )
    .width(Length::Fill);

    let next = card(
        column![
            section(app, app.t("ins.next_slots"), Some(app.t("ins.next_slots_text"))),
            column(data.suggestions.iter().take(4).filter_map(|s| local(&s.at).map(|d| (d, s.lift))).map(|(d, lift)| {
                dir_row(rtl, vec![column![strong(i.when(&d)), small(i.tv("ins.your_time", &[("lift", &format!("{}×", i.dec(lift, 1)))]))].spacing(2).into(), hspace(), btn(Some(Icon::CalendarPlus), app.t("ins.plan"), theme::secondary, Some(Message::NewPost(Some(d)))).into()]).spacing(10).align_y(Alignment::Center).into()
            }))
            .spacing(10),
        ]
        .spacing(12),
    )
    .width(Length::Fill);
    page = page.push(dir_row(rtl, vec![best.into(), next.into()]).spacing(14));

    let days: Vec<String> = (0..7).map(|k| i.weekday_short(k)).collect();
    let lift = data.heatmap.lift.clone();
    let posts = data.heatmap.posts.clone();
    let names: Vec<String> = (0..7).map(|k| i.weekday(k)).collect();
    let i2 = *i;
    let heat = Heatmap {
        lift: lift.clone(),
        days,
        tip: Box::new(move |d, h| {
            let l = lift.get(d).and_then(|r| r.get(h)).copied().unwrap_or(0.0);
            let n = posts.get(d).and_then(|r| r.get(h)).copied().unwrap_or(0);
            format!("{} {:02}:00 · {}× · {}", names[d], h, i2.dec(l, 1), i2.tn("ins.posts", n))
        }),
    };
    page = page.push(card(column![section(app, app.t("ins.heatmap"), Some(app.t("ins.heatmap_text"))), canvas(heat).width(Length::Fill).height(230)].spacing(12)).width(Length::Fill));

    let content = card(
        column![
            section(app, app.t("ins.resonates"), Some(app.t("ins.resonates_text"))),
            groups(app, app.t("ins.format"), &data.content.kinds, |app, k| label(app, "kind", k)),
            groups(app, app.t("ins.length"), &data.content.lengths, |app, k| label(app, "len", k)),
            groups(app, app.t("ins.topics"), &topics(data), |_, k| format!("#{k}")),
            groups(app, app.t("ins.links"), &data.content.links, |app, k| app.t(if k == "LINK" { "ins.with_link" } else { "ins.without_link" })),
        ]
        .spacing(16),
    )
    .width(Length::Fill);
    let top = card(
        column![
            section(app, app.t("ins.strongest"), Some(if data.metric_formula.is_empty() { app.t("ins.ranked") } else { i.tv("ins.ranked_by", &[("formula", &data.metric_formula)]) })),
            column(data.top_posts.iter().take(6).map(|p| {
                let preview = if p.preview.is_empty() { label(app, "kind", &p.kind) } else { clip(&p.preview, 90) };
                let mut meta = vec![small(local(&p.posted_at).map(|d| i.day_month(&d)).unwrap_or_default()).into(), small(i.tn("ins.reactions", p.reactions)).into()];
                if p.comments > 0 {
                    meta.push(small(i.tn("ins.comments", p.comments)).into());
                }
                let mut r = vec![column![strong(preview).size(13.5), dir_row(rtl, meta).spacing(8)].spacing(3).width(Length::Fill).into()];
                if let Some(url) = &p.url {
                    r.push(icon_btn(Icon::External, app.t("ins.open_tg"), Some(Message::OpenUrl(url.clone()))));
                }
                dir_row(rtl, r).spacing(8).align_y(Alignment::Center).into()
            }))
            .spacing(12),
            dir_row(rtl, vec![btn(Some(Icon::Wand), app.t("ins.more_like"), theme::soft, Some(Message::AssistantPrompt(app.t("ins.more_like_prompt")))).into()]),
        ]
        .spacing(12),
    )
    .width(Length::Fill);
    page = page.push(dir_row(rtl, vec![content.into(), top.into()]).spacing(14));
    page = page.push(small(i.tv("ins.footnote", &[("n", &i.dec(data.cadence.posts_per_week, 1)), ("formula", &data.metric_formula)])));
    page.into()
}

fn topics(data: &Insights) -> Vec<InsightGroup> {
    let win: Vec<_> = data.content.topics.iter().filter(|g| g.lift >= 1.1).take(3).cloned().collect();
    let lose: Vec<_> = data.content.topics.iter().filter(|g| g.lift <= 0.8).rev().take(2).cloned().collect();
    win.into_iter().chain(lose).collect()
}

fn label(app: &App, prefix: &str, key: &str) -> String {
    let k = format!("{prefix}.{key}");
    if app.i18n.has(&k) { app.t(&k) } else { key.to_string() }
}

fn groups<'a>(app: &'a App, title: String, groups: &[InsightGroup], name: impl Fn(&App, &str) -> String) -> Element<'a, Message> {
    let i = &app.i18n;
    let rtl = app.rtl();
    if groups.is_empty() {
        return column![strong(title), small(app.t("ins.topics_empty"))].spacing(6).into();
    }
    let max = groups.iter().map(|g| g.lift).fold(1.5, f64::max);
    let rows = groups.iter().map(|g| {
        let tone = if g.lift >= 1.05 { "success" } else if g.lift <= 0.95 { "danger" } else { "muted" };
        dir_row(rtl, vec![container(small(name(app, &g.key))).width(110).into(), meter((g.lift / max) as f32, tone), container(tinted(format!("{}×", i.dec(g.lift, 1)), tone).size(12)).width(42).align_x(Alignment::End).into()]).spacing(10).align_y(Alignment::Center).into()
    });
    column![strong(title)].push(column(rows).spacing(6)).spacing(8).into()
}
