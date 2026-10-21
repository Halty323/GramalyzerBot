//! Channel viewer: any channel's posts as Telegram shows them, with its numbers and an AI teardown.
use crate::app::{AiSlot, App, Loaded, Message, Source, local};
use crate::model::{FeedPost, TrackedStats};
use crate::pages::ai_output;
use crate::theme;
use crate::ui::*;
use crate::view::{channel_avatar, tracked_avatar};
use iced::widget::{button, column, container, image, row, text, text_input};
use iced::{Alignment, Element, Length, Padding};

pub fn view(app: &App) -> Element<'_, Message> {
    let rtl = app.rtl();
    dir_row(rtl, vec![sources(app), chat(app)]).spacing(14).height(Length::Fill).into()
}

fn sources(app: &App) -> Element<'_, Message> {
    let rtl = app.rtl();
    let v = &app.viewer;
    let input = text_input("@username / t.me/…", &v.input).on_input(Message::ViewerInput).on_submit(Message::ViewerOpen).padding(Padding::from([8, 10])).style(theme::input).font(INTER).size(13.5);
    let mut list = column![row![container(input).width(Length::Fill), icon_btn(Icon::Search, app.t("view.open"), Some(Message::ViewerOpen))].spacing(6).align_y(Alignment::Center), gap(4.0), eyebrow(app.t("side.channels"))].spacing(4);
    for c in app.channels() {
        let selected = v.source.as_ref() == Some(&Source::Mine(c.id.clone()));
        list = list.push(button(dir_row(rtl, vec![channel_avatar(app, c, 32.0), column![strong(c.name.clone()).size(13.5), small(c.handle.clone())].spacing(1).width(Length::Fill).into()]).spacing(10).align_y(Alignment::Center)).width(Length::Fill).padding(Padding::from([6, 8])).style(theme::list_item(selected)).on_press(Message::View(Source::Mine(c.id.clone()))));
    }
    list = list.push(gap(6.0)).push(eyebrow(app.t("side.watching")));
    for s in app.tracked.as_deref().unwrap_or(&[]) {
        let selected = matches!(&v.source, Some(Source::Public(u)) if u.eq_ignore_ascii_case(&s.username));
        let sub = s.subscribers.map(|n| app.i18n.tn("view.subscribers", n)).unwrap_or_else(|| format!("@{}", s.username));
        list = list.push(button(dir_row(rtl, vec![tracked_avatar(app, s, 32.0), column![strong(s.title.clone()).size(13.5), small(sub)].spacing(1).width(Length::Fill).into()]).spacing(10).align_y(Alignment::Center)).width(Length::Fill).padding(Padding::from([6, 8])).style(theme::list_item(selected)).on_press(Message::View(Source::Public(s.username.clone()))));
    }
    card(scroll(list).height(Length::Fill)).width(280).height(Length::Fill).into()
}

struct Chat {
    title: String,
    username: Option<String>,
    description: String,
    subscribers: Option<i64>,
    change7: Option<i64>,
    verified: bool,
    posts: Vec<FeedPost>,
    scheduled: Vec<FeedPost>,
    stats: TrackedStats,
    tracked: Option<bool>,
    url: Option<String>,
    mine: bool,
    photo: Option<image::Handle>,
    seed: String,
}

fn chat(app: &App) -> Element<'_, Message> {
    let i = &app.i18n;
    let rtl = app.rtl();
    let v = &app.viewer;
    let Some(loaded) = &v.loaded else {
        let body: Element<Message> = if v.loading {
            row![icon(Icon::Loader, 16), muted(app.t("view.loading"))].spacing(10).into()
        } else if let Some(e) = &v.error {
            banner(Icon::Alert, app.api_error(e), "danger")
        } else {
            column![icon_tinted(Icon::Messages, 26, "muted"), heading(app.t("view.pick_title"), 18), muted(app.t("view.pick_text"))].spacing(8).align_x(Alignment::Center).into()
        };
        return card(container(body).center(Length::Fill)).width(Length::Fill).height(Length::Fill).into();
    };
    let c = match loaded {
        Loaded::Feed(f) => {
            let ch = app.channel_by_id(&f.channel_id);
            let stats = f.stats.clone().unwrap_or_else(|| local_stats(&f.posts, ch.map(|c| c.subscribers)));
            Chat {
                title: ch.map(|c| c.name.clone()).unwrap_or_default(),
                username: ch.map(|c| c.handle.trim_start_matches('@').to_string()).filter(|u| !u.is_empty()),
                description: ch.map(|c| c.description.clone()).unwrap_or_default(),
                subscribers: stats.subscribers.or(ch.map(|c| c.subscribers)),
                change7: stats.change7,
                verified: false,
                posts: f.posts.clone(),
                scheduled: f.scheduled.clone(),
                stats,
                tracked: None,
                url: ch.filter(|_| !app.demo()).map(|c| format!("https://t.me/{}", c.handle.trim_start_matches('@'))).filter(|u| !u.ends_with('/')),
                mine: true,
                photo: ch.and_then(|c| app.photo_url(c)).and_then(|u| app.photos.get(&u).cloned()),
                seed: f.channel_id.clone(),
            }
        }
        Loaded::Detail(d) => Chat {
            title: d.channel.title.clone(),
            username: Some(d.channel.username.clone()),
            description: d.channel.description.clone().unwrap_or_default(),
            subscribers: d.channel.subscribers,
            change7: d.stats.change7,
            verified: d.channel.verified,
            posts: d.posts.clone(),
            scheduled: vec![],
            stats: d.stats.clone(),
            tracked: Some(app.tracked.as_ref().map(|l| l.iter().any(|s| s.username.eq_ignore_ascii_case(&d.channel.username))).unwrap_or(d.channel.tracked)),
            url: d.channel.url.clone(),
            mine: false,
            photo: app.tracked_photo(&d.channel).and_then(|u| app.photos.get(&u).cloned()),
            seed: d.channel.username.clone(),
        },
    };

    let avatar: Element<Message> = match &c.photo {
        Some(h) => image(h.clone()).width(46).height(46).border_radius(23.0).into(),
        None => initials_avatar(&c.title, &c.seed, 46.0),
    };
    let mut title_row = vec![heading(c.title.clone(), 18).into()];
    if c.verified {
        title_row.push(icon_tinted(Icon::Verified, 15, "accent"));
    }
    let mut sub_line = vec![];
    if let Some(u) = &c.username {
        sub_line.push(small(format!("@{u}")).into());
    }
    if let Some(n) = c.subscribers {
        sub_line.push(small(i.tn("view.subscribers", n)).into());
    }
    if let Some(ch) = c.change7 {
        sub_line.push(tinted(i.tv("view.in_7_days", &[("change", &i.signed(ch))]), if ch >= 0 { "success" } else { "danger" }).size(12).into());
    }
    let mut actions: Vec<Element<Message>> = vec![];
    if let (Some(t), Some(u)) = (c.tracked, &c.username) {
        actions.push(if t { btn(Some(Icon::Check), app.t("view.watching"), theme::secondary, Some(Message::WatchRemove(u.clone()))).into() } else { btn(Some(Icon::Telescope), app.t("view.track"), theme::primary, Some(Message::Track(u.clone()))).into() });
    }
    if c.mine {
        actions.push(btn(Some(Icon::Plus), app.t("act.new_post"), theme::primary, Some(Message::NewPost(None))).into());
    }
    actions.push(btn(Some(Icon::Sparkles), app.t("view.ai_teardown"), theme::soft, (!v.ai.running && !c.posts.is_empty()).then_some(Message::ViewerAi)).into());
    if let Some(url) = &c.url {
        actions.push(icon_btn(Icon::External, app.t("view.open_tg"), Some(Message::OpenUrl(url.clone()))));
    }
    actions.push(icon_btn(Icon::Refresh, app.t("top.refresh"), v.source.clone().map(Message::View)));
    let header = dir_row(rtl, vec![avatar, column![dir_row(rtl, title_row).spacing(6).align_y(Alignment::Center), dir_row(rtl, sub_line).spacing(10)].spacing(3).into(), hspace(), dir_row(rtl, actions).spacing(6).align_y(Alignment::Center).into()]).spacing(12).align_y(Alignment::Center);

    let st = &c.stats;
    let figure = |label: String, value: String| column![small(label), strong(value).size(15)].spacing(2);
    let strip = dir_row(
        rtl,
        vec![
            figure(app.t("view.avg_views"), st.avg_views.map(|x| i.compact(x)).unwrap_or("—".into())).into(),
            figure(app.t("view.er"), st.er.map(|x| i.percent(x, 1)).unwrap_or("—".into())).into(),
            figure(app.t("view.reaction_rate"), st.reaction_rate.map(|x| i.dec(x, 1)).unwrap_or("—".into())).into(),
            figure(app.t("view.per_day"), st.posts_per_day.map(|x| i.dec(x, 1)).unwrap_or("—".into())).into(),
            figure(app.t("view.last_post"), st.last_post.as_deref().and_then(local).map(|d| i.ago(&d)).unwrap_or("—".into())).into(),
        ],
    )
    .spacing(28);

    let mut col = column![header].spacing(14).height(Length::Fill);
    if !c.description.trim().is_empty() {
        col = col.push(small(c.description.clone()));
    }
    col = col.push(strip);
    if let Some(out) = ai_output(app, &v.ai, AiSlot::Viewer, 260.0) {
        col = col.push(out);
    }
    if c.mine && !c.scheduled.is_empty() {
        let tab = |on: bool, label: String| button(small(label)).padding(Padding::from([4, 12])).style(theme::chip(v.scheduled == on)).on_press(Message::ViewerScheduled(on));
        col = col.push(row![tab(false, i.tn("view.posts", c.posts.len() as i64)), tab(true, i.tn("view.scheduled", c.scheduled.len() as i64))].spacing(6));
    }
    let shown = if v.scheduled { &c.scheduled } else { &c.posts };
    let mut feed = column![].spacing(10).padding(Padding::from([6, 10]));
    if shown.is_empty() {
        feed = feed.push(container(muted(app.t("view.no_posts"))).center_x(Length::Fill).padding(30));
    }
    let mut ordered: Vec<&FeedPost> = shown.iter().collect();
    ordered.sort_by(|a, b| a.posted_at.cmp(&b.posted_at));
    for p in ordered {
        feed = feed.push(post(app, p, &c.title));
    }
    col = col.push(container(scroll(feed).anchor_bottom().height(Length::Fill)).style(theme::chat_wall).padding(6).height(Length::Fill));
    card(col).width(Length::Fill).height(Length::Fill).into()
}

fn post<'a>(app: &'a App, p: &FeedPost, channel: &str) -> Element<'a, Message> {
    let i = &app.i18n;
    let rtl = app.rtl();
    let kind_icon = match p.kind.as_str() {
        "PHOTO" | "ALBUM" => Some(Icon::Image),
        "VIDEO" | "ANIMATION" | "VIDEO_NOTE" => Some(Icon::Video),
        "TEXT" => None,
        _ => Some(Icon::FileText),
    };
    let mut col = column![text(channel.to_string()).font(INTER_SEMIBOLD).size(13).style(|th: &iced::Theme| text::Style { color: Some(theme::tokens(th).accent) })].spacing(5);
    if let Some(from) = &p.forwarded_from {
        col = col.push(row![icon_tinted(Icon::Forward, 12, "muted"), small(i.tv("view.forwarded", &[("name", from)]))].spacing(5));
    }
    if let Some(k) = kind_icon {
        let key = format!("kind.{}", p.kind);
        col = col.push(container(row![icon_tinted(k, 15, "muted"), small(if i.has(&key) { app.t(&key) } else { p.kind.clone() })].spacing(6).align_y(Alignment::Center)).padding(Padding::from([8, 10])).style(theme::inset).width(Length::Fill));
    }
    if !p.text.is_empty() {
        col = col.push(text(p.text.clone()).font(INTER).size(14).line_height(1.45));
    }
    if !p.reaction_list.is_empty() {
        col = col.push(row(p.reaction_list.iter().take(6).map(|r| container(small(format!("{} {}", r.emoji, i.compact(r.count as f64)))).padding(Padding::from([2, 8])).style(theme::badge("accent")).into())).spacing(5));
    }
    let mut meta: Vec<Element<Message>> = vec![hspace()];
    if let Some(c) = p.comments.filter(|c| *c > 0) {
        meta.push(row![icon_tinted(Icon::Message, 11, "muted"), faint(i.compact(c as f64))].spacing(3).align_y(Alignment::Center).into());
    }
    if let Some(views) = p.views {
        meta.push(row![icon_tinted(Icon::Eye, 11, "muted"), faint(i.compact(views as f64))].spacing(3).align_y(Alignment::Center).into());
    }
    if p.edited {
        meta.push(faint(app.t("view.edited")).into());
    }
    meta.push(faint(local(&p.posted_at).map(|d| format!("{} {}", i.day_month(&d), i.time(&d))).unwrap_or_default()).into());
    if let Some(url) = &p.url {
        meta.push(button(icon(Icon::External, 11)).padding(2).style(theme::ghost).on_press(Message::OpenUrl(url.clone())).into());
    }
    col = col.push(dir_row(rtl, meta).spacing(8).align_y(Alignment::Center));
    container(container(col).padding(Padding::from([9, 12])).style(theme::bubble).max_width(560)).width(Length::Fill).align_x(if rtl { Alignment::End } else { Alignment::Start }).into()
}

/// Figures from the posts themselves, for channels the backend has no preview statistics for.
fn local_stats(posts: &[FeedPost], subscribers: Option<i64>) -> TrackedStats {
    let viewed: Vec<i64> = posts.iter().filter_map(|p| p.views).collect();
    let avg_views = (!viewed.is_empty()).then(|| viewed.iter().sum::<i64>() as f64 / viewed.len() as f64);
    let reactions: i64 = posts.iter().filter(|p| p.views.is_some()).map(|p| p.reactions).sum();
    let times: Vec<_> = posts.iter().filter_map(|p| local(&p.posted_at)).collect();
    let (first, last) = (times.iter().min().copied(), times.iter().max().copied());
    let days = match (first, last) {
        (Some(a), Some(b)) => ((b - a).num_hours() as f64 / 24.0).max(1.0),
        _ => 1.0,
    };
    TrackedStats {
        subscribers,
        avg_views,
        er: avg_views.zip(subscribers.filter(|s| *s > 0)).map(|(v, s)| v / s as f64 * 100.0),
        avg_reactions: (!posts.is_empty()).then(|| posts.iter().map(|p| p.reactions).sum::<i64>() as f64 / posts.len() as f64),
        reaction_rate: (!viewed.is_empty() && viewed.iter().sum::<i64>() > 0).then(|| reactions as f64 / viewed.iter().sum::<i64>() as f64 * 100.0),
        posts_per_day: (posts.len() > 1).then(|| posts.len() as f64 / days),
        last_post: last.map(|d| d.to_rfc3339()),
        sample: posts.len() as i64,
        ..Default::default()
    }
}
