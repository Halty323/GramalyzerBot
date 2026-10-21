//! Watchlist: public channels to learn from, compared side by side.
use crate::app::{AiSlot, App, Message, Sort, Source, local};
use crate::charts::Sparkline;
use crate::model::TrackedSummary;
use crate::pages::{ai_output, section};
use crate::theme;
use crate::ui::*;
use crate::view::tracked_avatar;
use iced::widget::{button, canvas, column, container, row, text_input};
use iced::{Alignment, Element, Length, Padding};

const COLUMNS: [(Sort, &str, u16); 7] = [
    (Sort::Title, "watch.col.channel", 0),
    (Sort::Subscribers, "watch.col.subscribers", 110),
    (Sort::Change, "watch.col.change", 90),
    (Sort::Views, "watch.col.views", 90),
    (Sort::Er, "watch.col.er", 70),
    (Sort::PerDay, "watch.col.per_day", 80),
    (Sort::Last, "watch.col.last", 100),
];

fn key(s: &TrackedSummary, col: Sort) -> Option<f64> {
    match col {
        Sort::Title => None,
        Sort::Subscribers => s.subscribers.map(|v| v as f64),
        Sort::Change => s.change7.map(|v| v as f64),
        Sort::Views => s.avg_views,
        Sort::Er => s.er,
        Sort::PerDay => s.posts_per_day,
        Sort::Last => s.last_post.as_deref().and_then(local).map(|d| d.timestamp() as f64),
    }
}

pub fn view(app: &App) -> Element<'_, Message> {
    let i = &app.i18n;
    let rtl = app.rtl();
    let w = &app.watch;
    let input = text_input(&app.t("watch.add_placeholder"), &w.input).on_input(Message::WatchInput).on_submit(Message::WatchAdd).padding(10).style(theme::input).font(INTER).icon(text_input::Icon {
        font: ICONS,
        code_point: Icon::Telescope.char(),
        size: Some(15.0.into()),
        spacing: 8.0,
        side: text_input::Side::Left,
    });
    let mut add = column![
        section(app, app.t("watch.add_label"), Some(app.t("watch.add_text"))),
        dir_row(rtl, vec![container(input).width(Length::Fill).into(), btn(Some(if w.adding { Icon::Loader } else { Icon::Plus }), app.t("watch.add"), theme::primary, (!w.adding && !w.input.trim().is_empty()).then_some(Message::WatchAdd)).into()]).spacing(10).align_y(Alignment::Center),
    ]
    .spacing(12);
    if let Some(e) = &w.error {
        add = add.push(banner(Icon::Alert, e.clone(), "danger"));
    }
    let mut page = column![card(add).width(Length::Fill)].spacing(16);

    let Some(tracked) = &app.tracked else {
        return page.push(card(row![icon(Icon::Loader, 16), muted(app.t("view.loading"))].spacing(10))).into();
    };
    let mut rows: Vec<&TrackedSummary> = tracked.iter().collect();
    rows.sort_by(|a, b| {
        if w.sort == Sort::Title {
            let o = a.title.to_lowercase().cmp(&b.title.to_lowercase());
            return if w.desc { o.reverse() } else { o };
        }
        match (key(a, w.sort), key(b, w.sort)) {
            (Some(x), Some(y)) => {
                let o = x.partial_cmp(&y).unwrap_or(std::cmp::Ordering::Equal);
                if w.desc { o.reverse() } else { o }
            }
            (None, Some(_)) => std::cmp::Ordering::Greater,
            (Some(_), None) => std::cmp::Ordering::Less,
            _ => std::cmp::Ordering::Equal,
        }
    });

    if rows.len() >= 2 {
        let best = |f: fn(&TrackedSummary) -> Option<f64>| rows.iter().filter(|s| f(s).unwrap_or(0.0) > 0.0).max_by(|a, b| f(a).partial_cmp(&f(b)).unwrap_or(std::cmp::Ordering::Equal)).copied();
        let highlights: Vec<(Icon, &str, Option<&TrackedSummary>, String)> = vec![
            (Icon::Rocket, "watch.fastest", best(|s| s.change7.map(|v| v as f64)), String::new()),
            (Icon::Flame, "watch.engaging", best(|s| s.er), String::new()),
            (Icon::Zap, "watch.busiest", best(|s| s.posts_per_day), String::new()),
        ];
        let cards: Vec<Element<Message>> = highlights
            .into_iter()
            .filter_map(|(ic, label, s, _)| {
                let s = s?;
                let line = match label {
                    "watch.fastest" => i.tv("view.in_7_days", &[("change", &i.signed(s.change7.unwrap_or(0)))]),
                    "watch.engaging" => format!("{} {}", app.t("watch.col.er"), i.percent(s.er.unwrap_or(0.0), 1)),
                    _ => i.tv("watch.per_day", &[("n", &i.dec(s.posts_per_day.unwrap_or(0.0), 1))]),
                };
                Some(
                    button(dir_row(rtl, vec![container(icon_tinted(ic, 17, "accent")).center(38).style(theme::badge("accent")).into(), column![small(app.t(label)), strong(s.title.clone()), tinted(line, "accent").size(12)].spacing(2).into()]).spacing(12).align_y(Alignment::Center))
                        .padding(14)
                        .width(Length::Fill)
                        .style(theme::tile)
                        .on_press(Message::View(Source::Public(s.username.clone())))
                        .into(),
                )
            })
            .collect();
        page = page.push(dir_row(rtl, cards).spacing(14));
    }

    let mut table = column![dir_row(rtl, vec![section(app, app.t("watch.title"), Some(app.t("watch.text"))), hspace(), btn(Some(Icon::Sparkles), app.t("watch.ai_compare"), theme::soft, (!w.ai.running && !rows.is_empty()).then_some(Message::WatchAi)).into()]).align_y(Alignment::Center)].spacing(10);
    if let Some(out) = ai_output(app, &w.ai, AiSlot::Watchlist, 360.0) {
        table = table.push(out);
    }
    if rows.is_empty() {
        table = table.push(container(column![icon_tinted(Icon::Telescope, 26, "muted"), strong(app.t("watch.empty_title")), muted(app.t("watch.empty_text"))].spacing(8).align_x(Alignment::Center)).center_x(Length::Fill).padding(30));
        return page.push(card(table).width(Length::Fill)).into();
    }
    let header_cells: Vec<Element<Message>> = COLUMNS
        .iter()
        .map(|(col, label, width)| {
            let arrow = if w.sort == *col { if w.desc { " ↓" } else { " ↑" } } else { "" };
            let b = button(small(format!("{}{arrow}", app.t(label)))).padding(Padding::from([4, 0])).style(theme::ghost).on_press(Message::WatchSort(*col));
            let c = container(b);
            if *width == 0 { c.width(Length::Fill).into() } else { c.width(*width as f32).into() }
        })
        .chain([container(small(app.t("watch.col.trend"))).width(90).into(), container(gap(0.0)).width(110).into()])
        .collect();
    table = table.push(dir_row(rtl, header_cells).spacing(8).align_y(Alignment::Center)).push(hr());
    for s in rows {
        let num = |v: Option<String>| -> Element<Message> { body(v.unwrap_or("—".into())).into() };
        let mut name = vec![strong(s.title.clone()).into()];
        if s.verified {
            name.push(icon_tinted(Icon::Verified, 13, "accent"));
        }
        if s.demo {
            name.push(badge(app.t("app.sample"), "muted"));
        }
        if let Some(e) = &s.error {
            name.push(with_tip(icon_tinted(Icon::Alert, 13, "warning"), app.t(&format!("err.{e}"))));
        }
        let who = button(dir_row(rtl, vec![tracked_avatar(app, s, 34.0), column![dir_row(rtl, name).spacing(6).align_y(Alignment::Center), small(format!("@{}", s.username))].spacing(2).into()]).spacing(10).align_y(Alignment::Center)).padding(4).style(theme::list_item(false)).on_press(Message::View(Source::Public(s.username.clone())));
        let change: Element<Message> = match s.change7 {
            Some(c) => tinted(i.signed(c), if c >= 0 { "success" } else { "danger" }).into(),
            None => body("—").into(),
        };
        let actions: Element<Message> = if w.removing.as_deref() == Some(&s.username) {
            row![small(app.t("watch.remove_q")), button(small(app.t("app.yes"))).padding(Padding::from([3, 8])).style(theme::danger).on_press(Message::WatchRemove(s.username.clone())), button(small(app.t("app.cancel"))).padding(Padding::from([3, 8])).style(theme::ghost).on_press(Message::WatchAskRemove(None))].spacing(4).align_y(Alignment::Center).into()
        } else {
            row![
                icon_btn(Icon::Eye, i.tv("watch.open", &[("name", &s.title)]), Some(Message::View(Source::Public(s.username.clone())))),
                icon_btn(if w.refreshing.as_deref() == Some(&s.username) { Icon::Loader } else { Icon::Refresh }, i.tv("watch.refresh", &[("name", &s.title)]), w.refreshing.is_none().then(|| Message::WatchRefresh(s.username.clone()))),
                icon_btn(Icon::Trash, i.tv("watch.remove", &[("name", &s.title)]), Some(Message::WatchAskRemove(Some(s.username.clone())))),
            ]
            .spacing(2)
            .into()
        };
        let cells: Vec<Element<Message>> = vec![
            container(who).width(Length::Fill).into(),
            container(num(s.subscribers.map(|v| i.num(v)))).width(110).into(),
            container(change).width(90).into(),
            container(num(s.avg_views.map(|v| i.compact(v)))).width(90).into(),
            container(num(s.er.map(|v| i.percent(v, 1)))).width(70).into(),
            container(num(s.posts_per_day.map(|v| i.dec(v, 1)))).width(80).into(),
            container(small(s.last_post.as_deref().and_then(local).map(|d| i.ago(&d)).unwrap_or_else(|| app.t("watch.never")))).width(100).into(),
            container(canvas(Sparkline(s.series.clone())).width(80).height(26)).width(90).into(),
            container(actions).width(110).into(),
        ];
        table = table.push(dir_row(rtl, cells).spacing(8).align_y(Alignment::Center));
    }
    table = table.push(hr()).push(small(app.t("watch.footnote")));
    page.push(card(table).width(Length::Fill)).into()
}
