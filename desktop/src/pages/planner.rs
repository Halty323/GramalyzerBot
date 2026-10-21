//! Planner: the week as a content calendar, the bot's best slots marked, and an AI planner that drafts a batch of posts.
use crate::app::{App, Message, local};
use crate::model::PostStatus;
use crate::pages::{ai_unready_card, clip, section};
use crate::theme;
use crate::ui::*;
use crate::view::{Choice, channel_avatar, picker};
use chrono::{Datelike, Duration, Local, NaiveDate, TimeZone};
use iced::widget::{button, column, container, row, text, text_input};
use iced::{Alignment, Element, Length, Padding};

pub fn view(app: &App) -> Element<'_, Message> {
    let i = &app.i18n;
    let rtl = app.rtl();
    let today = Local::now().date_naive();
    let monday = today - Duration::days(today.weekday().num_days_from_monday() as i64) + Duration::weeks(app.planner.week);
    let sunday = monday + Duration::days(6);
    let at_noon = |d: NaiveDate| Local.from_local_datetime(&d.and_hms_opt(12, 0, 0).unwrap()).earliest().unwrap();
    let range = format!("{} – {}", i.day_month(&at_noon(monday)), i.day_month(&at_noon(sunday)));

    let nav = dir_row(
        rtl,
        vec![
            column![eyebrow(app.t("plan.eyebrow")), heading(range, 22)].spacing(4).into(),
            hspace(),
            icon_btn(if rtl { Icon::ChevronRight } else { Icon::ArrowLeft }, app.t("plan.prev"), Some(Message::Week(app.planner.week - 1))),
            btn(None, app.t("plan.this_week"), theme::secondary, (app.planner.week != 0).then_some(Message::Week(0))).into(),
            icon_btn(if rtl { Icon::ArrowLeft } else { Icon::ChevronRight }, app.t("plan.next"), Some(Message::Week(app.planner.week + 1))),
            btn(Some(Icon::Sparkles), app.t("plan.ai"), theme::soft, Some(Message::PlanPanel(!app.planner.panel))).into(),
        ],
    )
    .spacing(8)
    .align_y(Alignment::Center);

    let mut page = column![nav].spacing(16);
    if app.planner.panel {
        page = page.push(if app.ai_ready() { ai_panel(app) } else { ai_unready_card(app) });
    }

    let focus = app.focus_channel();
    let suggestions: Vec<(chrono::DateTime<Local>, f64)> = focus
        .and_then(|c| app.insights.get(&c.id))
        .and_then(|r| r.as_ref().ok())
        .map(|ins| {
            let tz: chrono_tz::Tz = ins.timezone.parse().unwrap_or(chrono_tz::UTC);
            ins.best_times.iter().take(5).map(|s| (s.day.clone(), s.hour, s.lift, tz)).collect::<Vec<_>>()
        })
        .unwrap_or_default()
        .into_iter()
        .filter_map(|(day, hour, lift, tz)| {
            // Best times are hours in the channel's timezone; show them on this computer's clock.
            let idx = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"].iter().position(|d| *d == day)? as i64;
            let date = monday + Duration::days(idx);
            tz.from_local_datetime(&date.and_hms_opt(hour, 0, 0)?).earliest().map(|d| (d.with_timezone(&Local), lift))
        })
        .collect();

    let mut days: Vec<Element<Message>> = vec![];
    for k in 0..7 {
        let date = monday + Duration::days(k);
        let is_today = date == today;
        let mut posts: Vec<_> = app
            .posts()
            .iter()
            .filter(|p| app.channel.as_ref().is_none_or(|c| &p.channel_id == c))
            .filter_map(|p| local(&p.scheduled_at).filter(|d| d.date_naive() == date).map(|d| (d, p)))
            .collect();
        posts.sort_by_key(|(d, _)| *d);
        let mut best: Vec<_> = suggestions.iter().filter(|(d, _)| d.date_naive() == date).collect();
        best.sort_by_key(|(d, _)| *d);

        let head = dir_row(
            rtl,
            vec![
                column![small(i.weekday_short(k as usize)), heading(date.day().to_string(), 20).style(move |th: &iced::Theme| text::Style { color: Some(if is_today { theme::tokens(th).accent } else { theme::tokens(th).text }) })].spacing(0).into(),
                hspace(),
                if is_today { badge(app.t("plan.today"), "accent") } else { gap(0.0) },
            ],
        )
        .align_y(Alignment::Center);
        let mut col = column![head].spacing(6);
        for (d, lift) in &best {
            let slot = *d;
            let future = slot > Local::now();
            col = col.push(
                button(dir_row(rtl, vec![icon_tinted(Icon::Zap, 11, "accent"), tinted(format!("{} · {}×", i.time(&slot), i.dec(*lift, 1)), "accent").size(11.5).into()]).spacing(5).align_y(Alignment::Center))
                    .padding(Padding::from([4, 7]))
                    .width(Length::Fill)
                    .style(theme::soft)
                    .on_press_maybe(future.then_some(Message::NewPost(Some(slot)))),
            );
        }
        for (d, p) in posts {
            let tone = match p.status {
                PostStatus::Published => "success",
                PostStatus::Scheduled => "accent",
                PostStatus::Draft => "muted",
            };
            let channel = app.channel_by_id(&p.channel_id);
            let mut meta: Vec<Element<Message>> = vec![tinted(i.time(&d), tone).size(11.5).into()];
            if app.channel.is_none() {
                if let Some(c) = channel {
                    meta.push(channel_avatar(app, c, 14.0));
                }
            }
            col = col.push(
                button(column![dir_row(rtl, meta).spacing(5).align_y(Alignment::Center), text(clip(&p.title, 60)).font(INTER).size(12.5)].spacing(3))
                    .padding(Padding::from([6, 8]))
                    .width(Length::Fill)
                    .style(theme::tile)
                    .on_press(Message::OpenPost(p.id.clone())),
            );
        }
        let add_at = best.iter().map(|(d, _)| *d).find(|d| *d > Local::now()).or_else(|| Local.from_local_datetime(&date.and_hms_opt(10, 0, 0).unwrap()).earliest().filter(|d| *d > Local::now()));
        col = col.push(button(container(icon(Icon::Plus, 13)).center_x(Length::Fill)).padding(5).width(Length::Fill).style(theme::ghost).on_press_maybe(add_at.map(|d| Message::NewPost(Some(d)))));
        let day_card = container(col).padding(10).height(Length::Shrink).width(Length::Fill).style(move |th: &iced::Theme| {
            let mut s = theme::card(th);
            if is_today {
                s.border.color = theme::tokens(th).accent;
            }
            s
        });
        days.push(day_card.into());
    }
    page = page.push(dir_row(rtl, days).spacing(10).align_y(Alignment::Start));

    let legend = dir_row(
        rtl,
        vec![
            badge(app.t("status.scheduled"), "accent"),
            badge(app.t("status.draft"), "muted"),
            badge(app.t("status.published"), "success"),
            row![icon_tinted(Icon::Zap, 12, "accent"), small(match focus {
                Some(c) => i.tv("plan.best_for", &[("name", &c.name)]),
                None => app.t("plan.best_none"),
            })]
            .spacing(5)
            .align_y(Alignment::Center)
            .into(),
        ],
    )
    .spacing(10)
    .align_y(Alignment::Center);
    page.push(legend).into()
}

fn ai_panel(app: &App) -> Element<'_, Message> {
    let p = &app.planner;
    let i = &app.i18n;
    let rtl = app.rtl();
    let Some(channel) = app.focus_channel() else {
        return card(muted(app.t("studio.no_channels"))).width(Length::Fill).into();
    };
    let counts: Vec<Choice> = (3..=10).map(|n| Choice::new(n.to_string(), i.tn("plan.n_posts", n))).collect();
    let controls = dir_row(
        rtl,
        vec![
            // Same height as the focus input, so the three labels line up.
            field(app.t("plan.channel"), container(row![channel_avatar(app, channel, 22.0), strong(channel.name.clone())].spacing(8).align_y(Alignment::Center)).height(36).align_y(Alignment::Center), None),
            field(app.t("plan.how_many"), container(picker(counts, &p.count.to_string(), |s| Message::PlanCount(s.parse().unwrap_or(5)))).height(36).align_y(Alignment::Center), None),
            container(field(app.t("plan.focus"), text_input(&app.t("plan.focus_placeholder"), &p.brief).on_input(Message::PlanBrief).on_submit(Message::PlanRun).padding(9).style(theme::input).font(INTER).size(13.5), None)).width(Length::Fill).into(),
            container(btn(Some(if p.running { Icon::Loader } else { Icon::Wand }), app.t(if p.running { "plan.thinking" } else { "plan.generate" }), theme::primary, (!p.running).then_some(Message::PlanRun))).align_y(Alignment::End).height(Length::Shrink).into(),
        ],
    )
    .spacing(14)
    .align_y(Alignment::End);
    let mut col = column![section(app, app.t("plan.ai_title"), Some(app.t("plan.ai_text"))), controls].spacing(14);
    if let Some(e) = &p.error {
        col = col.push(banner(Icon::Alert, e.clone(), "danger"));
    }
    if !p.ideas.is_empty() {
        let mut list = column![].spacing(8);
        for (k, idea) in p.ideas.iter().enumerate() {
            let check = button(container(if idea.keep { icon(Icon::Check, 13) } else { gap(13.0) }).center(16)).padding(3).style(theme::chip(idea.keep)).on_press(Message::PlanToggle(k));
            let body = column![
                dir_row(rtl, vec![strong(idea.post.title.clone()).into(), badge(idea.post.category.clone(), "muted"), hspace(), tinted(i.when(&idea.at), "accent").size(12).into()]).spacing(8).align_y(Alignment::Center),
                small(clip(&idea.post.body, 260)),
            ]
            .spacing(4)
            .width(Length::Fill);
            list = list.push(container(dir_row(rtl, vec![check.into(), body.into()]).spacing(12).align_y(Alignment::Start)).padding(12).style(theme::inset).width(Length::Fill));
        }
        let keep = p.ideas.iter().filter(|x| x.keep).count() as i64;
        col = col.push(container(scroll(list)).max_height(420.0)).push(
            dir_row(
                rtl,
                vec![
                    small(app.t("plan.drafts_note")).into(),
                    hspace(),
                    btn(None, app.t("plan.again"), theme::ghost, (!p.running).then_some(Message::PlanRun)).into(),
                    btn(Some(Icon::CalendarPlus), i.tn("plan.create", keep), theme::primary, (keep > 0 && !p.creating).then_some(Message::PlanCreate)).into(),
                ],
            )
            .spacing(10)
            .align_y(Alignment::Center),
        );
    }
    card(col).width(Length::Fill).into()
}
