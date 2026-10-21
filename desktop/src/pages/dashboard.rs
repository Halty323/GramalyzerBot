//! Dashboard: the workspace at a glance, and what to do next.
use crate::app::{App, Message, Page, local};
use crate::charts::LineChart;
use crate::model::{Post, PostStatus};
use crate::pages::{ai_unready_card, post_row, section};
use crate::theme;
use crate::ui::*;
use chrono::{Duration, Local};
use iced::widget::{button, canvas, column, container, row};
use iced::{Alignment, Element, Length, Padding};

pub fn view(app: &App) -> Element<'_, Message> {
    let i = &app.i18n;
    let rtl = app.rtl();
    let demo = app.demo();
    let channels: Vec<_> = app.channels().iter().filter(|c| app.channel.as_ref().is_none_or(|id| &c.id == id)).collect();
    let since = Local::now() - Duration::days(app.period);
    let posts: Vec<&Post> = app.posts().iter().filter(|p| channels.iter().any(|c| c.id == p.channel_id)).collect();
    let published: Vec<&Post> = posts.iter().copied().filter(|p| p.status == PostStatus::Published && local(&p.scheduled_at).is_some_and(|d| d >= since)).collect();
    let mut queued: Vec<&Post> = posts.iter().copied().filter(|p| p.status == PostStatus::Scheduled).collect();
    queued.sort_by(|a, b| a.scheduled_at.cmp(&b.scheduled_at));
    let drafts: Vec<&Post> = posts.iter().copied().filter(|p| p.status == PostStatus::Draft).collect();
    let subscribers: i64 = channels.iter().map(|c| c.subscribers).sum();
    let growth = if channels.is_empty() { 0.0 } else { channels.iter().map(|c| c.growth).sum::<f64>() / channels.len() as f64 };
    let views: i64 = published.iter().map(|p| p.views).sum();
    let reactions: i64 = published.iter().map(|p| p.reactions).sum();

    let periods = row([7, 28, 90].map(|d| button(small(i.tn("filter.days", d))).padding(Padding::from([5, 12])).style(theme::chip(app.period == d)).on_press(Message::Period(d)).into())).spacing(6);
    let scope = match app.channel.as_ref().and_then(|id| app.channel_by_id(id)) {
        Some(c) => c.name.clone(),
        None => i.tn("dash.all_channels", channels.len() as i64),
    };
    let head = dir_row(rtl, vec![column![eyebrow(app.t("dash.eyebrow")), heading(scope, 24)].spacing(4).into(), hspace(), periods.into()]).align_y(Alignment::End);

    let stats = row![
        stat(Icon::Users, "accent", app.t("stat.subscribers"), i.num(subscribers), if demo { i.tv("stat.growth", &[("growth", &i.percent(growth, 1))]) } else { app.t("stat.observed") }, rtl),
        stat(Icon::Eye, "accent", app.t("stat.views"), if demo { i.num(views) } else { "—".into() }, if demo { i.tn("stat.views_note", published.len() as i64) } else { app.t("stat.views_hidden") }, rtl),
        stat(
            Icon::Message,
            "warning",
            app.t(if demo { "stat.engagement" } else { "stat.reactions_per_post" }),
            if demo && views > 0 { i.percent(reactions as f64 / views as f64 * 100.0, 2) } else if !published.is_empty() { i.dec(reactions as f64 / published.len() as f64, 1) } else { "—".into() },
            if published.is_empty() { app.t("stat.after_publishing") } else { i.tn("stat.average_over", published.len() as i64) },
            rtl
        ),
        stat(Icon::Send, "success", app.t("stat.published"), i.num(published.len() as i64), i.tn("stat.queued", queued.len() as i64), rtl),
    ]
    .spacing(14);

    let audience = card(column![section(app, app.t("aud.title"), Some(app.t(if demo { "aud.sample" } else { "aud.text" }))), audience_chart(app, &channels)].spacing(12)).width(Length::FillPortion(3));

    let next = next_card(app);
    let middle = dir_row(rtl, vec![audience.into(), next]).spacing(14);

    let up_next = card(
        column![
            dir_row(rtl, vec![section(app, app.t("dash.up_next"), None), hspace(), button(small(app.t("dash.open_planner"))).style(theme::link).padding(0).on_press(Message::Navigate(Page::Planner)).into()]).align_y(Alignment::Center),
            if queued.is_empty() { column![muted(app.t("dash.queue_empty"))] } else { column(queued.iter().take(5).map(|p| post_row(app, p))).spacing(4) }
        ]
        .spacing(10),
    )
    .width(Length::Fill);

    let mut top: Vec<&Post> = published.clone();
    top.sort_by_key(|p| std::cmp::Reverse(if demo { p.views } else { p.reactions }));
    let top_posts = card(
        column![
            dir_row(rtl, vec![section(app, app.t("dash.top_posts"), None), hspace(), button(small(app.t("dash.library"))).style(theme::link).padding(0).on_press(Message::Navigate(Page::Studio)).into()]).align_y(Alignment::Center),
            if top.is_empty() { column![muted(app.t("dash.no_published"))] } else { column(top.iter().take(5).map(|p| post_row(app, p))).spacing(4) }
        ]
        .spacing(10),
    )
    .width(Length::Fill);

    let draft_card = card(
        column![
            dir_row(rtl, vec![section(app, app.t("dash.drafts"), None), hspace(), badge(i.num(drafts.len() as i64), "muted")]).align_y(Alignment::Center),
            if drafts.is_empty() { column![muted(app.t("dash.no_drafts"))] } else { column(drafts.iter().take(4).map(|p| post_row(app, p))).spacing(4) },
            dir_row(rtl, vec![
                btn(Some(Icon::Sparkles), app.t("dash.plan_week"), theme::soft, Some(Message::PlanPanel(true))).into(),
                btn(Some(Icon::PenLine), app.t("act.new_post"), theme::secondary, Some(Message::NewPost(None))).into(),
            ])
            .spacing(8),
        ]
        .spacing(10),
    )
    .width(Length::Fill);

    let bottom = dir_row(rtl, vec![up_next.into(), top_posts.into(), draft_card.into()]).spacing(14);
    let mut page = column![head, stats, middle, bottom].spacing(16);
    if !app.ai_ready() {
        page = page.push(ai_unready_card(app));
    }
    page.into()
}

fn audience_chart<'a>(app: &'a App, channels: &[&crate::model::Channel]) -> Element<'a, Message> {
    let i = &app.i18n;
    let history = app.workspace.as_ref().map(|w| w.history.as_slice()).unwrap_or(&[]);
    let since = (Local::now() - Duration::days(app.period)).date_naive();
    let mut days: std::collections::BTreeMap<String, (i64, usize)> = Default::default();
    for h in history.iter().filter(|h| channels.iter().any(|c| c.id == h.channel_id)) {
        if chrono::NaiveDate::parse_from_str(&h.date, "%Y-%m-%d").is_ok_and(|d| d >= since) {
            let e = days.entry(h.date.clone()).or_default();
            e.0 += h.subscribers;
            e.1 += 1;
        }
    }
    let mut points: Vec<(chrono::NaiveDate, f64)> = days.into_iter().filter(|(_, (_, n))| *n == channels.len()).filter_map(|(d, (v, _))| chrono::NaiveDate::parse_from_str(&d, "%Y-%m-%d").ok().map(|d| (d, v as f64))).collect();
    if points.len() < 2 && app.demo() {
        // The demo workspace has no history yet: draw the sample growth the channel cards claim.
        let now: f64 = channels.iter().map(|c| c.subscribers as f64).sum();
        let growth = if channels.is_empty() { 0.0 } else { channels.iter().map(|c| c.growth).sum::<f64>() / channels.len() as f64 };
        let start = now / (1.0 + growth / 100.0 * app.period as f64 / 28.0);
        let today = Local::now().date_naive();
        points = (0..=28)
            .map(|k| {
                let wobble = if k == 0 || k == 28 { 0.0 } else { (k as f64 * 1.8).sin() * 0.016 };
                let day = today - Duration::days(app.period - (k as i64 * app.period / 28));
                (day, (start + (now - start) * (k as f64 / 28.0 + wobble)).round())
            })
            .collect();
    }
    if points.len() < 2 {
        return container(column![icon_tinted(Icon::Chart, 22, "muted"), strong(app.t("aud.empty_title")), muted(app.t("aud.empty_text"))].spacing(6).align_x(Alignment::Center)).center_x(Length::Fill).height(210).center_y(210).into();
    }
    let labels = points.iter().map(|(d, _)| i.day_month(&d.and_hms_opt(12, 0, 0).unwrap().and_local_timezone(Local).unwrap())).collect::<Vec<_>>();
    let tips = points.iter().zip(&labels).map(|((_, v), l)| format!("{l} · {}", i.num(*v as i64))).collect();
    canvas(LineChart { values: points.iter().map(|p| p.1).collect(), labels, tips, format: |v| crate::i18n::I18n::new("en").compact(v) }).width(Length::Fill).height(220).into()
}

fn next_card(app: &App) -> Element<'_, Message> {
    let i = &app.i18n;
    let channel = app.focus_channel();
    let insight = channel.and_then(|c| app.insights.get(&c.id)).and_then(|r| r.as_ref().ok());
    let mut body = column![row![icon_tinted(Icon::Zap, 13, "accent"), eyebrow(app.t("rhythm.eyebrow"))].spacing(6).align_y(Alignment::Center)].spacing(10);
    match (channel, insight) {
        (Some(c), Some(ins)) if ins.status == "ready" && !ins.suggestions.is_empty() => {
            let s = &ins.suggestions[0];
            let at = local(&s.at);
            body = body
                .push(heading(at.map(|d| i.when(&d)).unwrap_or_default(), 20))
                .push(muted(i.tv("rhythm.text", &[("name", &c.name), ("lift", &format!("{}×", i.dec(s.lift, 1)))])))
                .push(column(ins.suggestions.iter().skip(1).take(3).filter_map(|s| local(&s.at).map(|d| (d, s.lift))).map(|(d, lift)| {
                    dir_row(app.rtl(), vec![icon_tinted(Icon::Clock, 13, "muted"), small(i.when(&d)).into(), hspace(), tinted(format!("{}×", i.dec(lift, 1)), "accent").into()]).spacing(8).align_y(Alignment::Center).into()
                })).spacing(6))
                .push(btn(Some(Icon::CalendarPlus), app.t("rhythm.plan"), theme::primary, at.map(|d| Message::NewPost(Some(d)))).width(Length::Fill));
        }
        (Some(c), Some(ins)) if ins.status == "learning" => {
            body = body.push(heading(i.tv("rhythm.learning", &[("name", &c.name)]), 18)).push(muted(i.tn("rhythm.learning_text", ins.posts_needed)));
        }
        _ => {
            body = body.push(heading(app.t("rhythm.fallback_title"), 18)).push(muted(app.t("rhythm.fallback_text")));
        }
    }
    body = body.push(button(small(app.t("rhythm.see_insights"))).style(theme::link).padding(0).on_press(Message::Navigate(Page::Insights)));
    card(body).width(Length::FillPortion(2)).into()
}
