//! Studio: the content library, the post editor and the AI copilot side by side.
use crate::app::{AiSlot, App, ApplyMode, Message, Rewrite, TONES, local, parse_when};
use crate::i18n::LANGS;
use crate::model::{Post, PostStatus};
use crate::pages::{ai_output, knows, post_row, section, status_badge};
use crate::theme;
use crate::ui::*;
use crate::view::{Choice, channel_avatar, picker};
use iced::widget::{Space, button, column, container, row, text, text_editor, text_input};
use iced::{Alignment, Element, Length, Padding};

const LIMIT: usize = 4096;

pub fn view(app: &App) -> Element<'_, Message> {
    let rtl = app.rtl();
    let panes = vec![library(app), editor(app), copilot(app)];
    dir_row(rtl, panes).spacing(14).height(Length::Fill).into()
}

fn library(app: &App) -> Element<'_, Message> {
    let s = &app.studio;
    let posts = app.library();
    let all = app.posts().iter().filter(|p| app.channel.as_ref().is_none_or(|c| &p.channel_id == c)).collect::<Vec<_>>();
    let count = |f: Option<PostStatus>| all.iter().filter(|p| f.is_none_or(|f| p.status == f)).count() as i64;
    let chip = |f: Option<PostStatus>, key: &str| {
        button(small(format!("{} {}", app.t(key), app.i18n.num(count(f))))).padding(Padding::from([4, 10])).style(theme::chip(s.filter == f)).on_press(Message::StudioFilter(f))
    };
    let filters = column![
        row![chip(None, "studio.all"), chip(Some(PostStatus::Draft), "studio.drafts")].spacing(6),
        row![chip(Some(PostStatus::Scheduled), "studio.scheduled"), chip(Some(PostStatus::Published), "studio.published")].spacing(6),
    ]
    .spacing(6);
    let search = text_input(&app.t("studio.search"), &s.search).on_input(Message::StudioSearch).padding(Padding::from([8, 10])).style(theme::input).font(INTER).size(13.5).icon(text_input::Icon {
        font: ICONS,
        code_point: Icon::Search.char(),
        size: Some(14.0.into()),
        spacing: 8.0,
        side: text_input::Side::Left,
    });
    let list: Element<Message> = if posts.is_empty() {
        container(column![icon_tinted(Icon::FileText, 22, "muted"), muted(app.t("studio.empty"))].spacing(8).align_x(Alignment::Center)).padding(24).center_x(Length::Fill).into()
    } else {
        scroll(column(posts.into_iter().map(|p| post_row(app, p))).spacing(2)).height(Length::Fill).into()
    };
    card(column![section(app, app.t("studio.library"), Some(app.t("studio.library_text"))), filters, search, list].spacing(12).height(Length::Fill)).width(300).height(Length::Fill).into()
}

fn editor(app: &App) -> Element<'_, Message> {
    let s = &app.studio;
    let i = &app.i18n;
    let rtl = app.rtl();
    if !s.open {
        return card(
            column![
                container(icon_tinted(Icon::PenLine, 26, "accent")).center(60).style(theme::badge("accent")),
                heading(app.t("studio.start_title"), 22),
                muted(app.t("studio.start_text")).align_x(iced::alignment::Horizontal::Center),
                row![btn(Some(Icon::Plus), app.t("act.new_post"), theme::primary, Some(Message::NewPost(None))), btn(Some(Icon::Sparkles), app.t("studio.plan_ai"), theme::soft, Some(Message::PlanPanel(true)))].spacing(10),
                faint(app.t("studio.shortcuts")),
            ]
            .spacing(14)
            .align_x(Alignment::Center)
            .max_width(440),
        )
        .center(Length::Fill)
        .height(Length::Fill)
        .width(Length::Fill)
        .into();
    }
    let post = app.editing_post();
    let read_only = post.is_some_and(Post::read_only);
    let title_key = if read_only { "studio.view_post" } else if post.is_some() { "studio.edit_post" } else { "studio.new_post" };
    let mut head = vec![heading(app.t(title_key), 18).into()];
    if let Some(p) = post {
        head.push(status_badge(app, p));
    }
    if s.dirty {
        head.push(badge(app.t("studio.unsaved"), "warning"));
    }
    head.push(hspace());
    head.push(icon_btn(Icon::X, app.t("studio.close"), Some(Message::CloseEditor)));
    let header = dir_row(rtl, head).spacing(10).align_y(Alignment::Center);

    let channels: Vec<Choice> = app.channels().iter().map(|c| Choice::new(c.id.clone(), c.name.clone())).collect();
    let channel_pick: Element<Message> = if read_only { body(app.channel_by_id(&s.channel_id).map(|c| c.name.clone()).unwrap_or_default()).into() } else { picker(channels, &s.channel_id, Message::PostChannel) };
    let title_input = text_input(&app.t("studio.title_placeholder"), &s.title).on_input_maybe((!read_only).then_some(Message::Title)).padding(10).style(theme::input).font(INTER_SEMIBOLD).size(16);
    let top = dir_row(rtl, vec![field(app.t("studio.channel"), channel_pick, None), container(field(app.t("studio.title"), title_input, None)).width(Length::Fill).into()]).spacing(12);

    let mut body_editor = text_editor(&s.body).placeholder(app.t("studio.body_placeholder"));
    if !read_only {
        body_editor = body_editor.on_action(Message::Body);
    }
    let body_editor = body_editor
        .padding(14)
        .font(INTER)
        .size(15)
        .line_height(1.55)
        .height(Length::Fill)
        .style(theme::editor);
    let text_now = s.body.text();
    let chars = text_now.trim_end().chars().count();
    let signature = app.channel_by_id(&s.channel_id).map(|c| c.signature.clone()).unwrap_or_default();
    let total = chars + if signature.is_empty() { 0 } else { signature.chars().count() + 2 };
    let words = text_now.split_whitespace().count() as i64;
    let tone = if total > LIMIT { "danger" } else if total > 3500 { "warning" } else { "muted" };
    let counter = dir_row(
        rtl,
        vec![
            tinted(i.tv("studio.chars", &[("n", &i.num(total as i64)), ("max", &i.num(LIMIT as i64))]), tone).size(12).into(),
            faint("·").into(),
            small(i.tn("studio.words", words)).into(),
            faint("·").into(),
            small(i.tn("studio.read_time", ((words as f64 / 200.0).ceil() as i64).max(1))).into(),
            hspace(),
            if signature.is_empty() { Space::new().into() } else { small(app.t("studio.signature_added")).into() },
        ],
    )
    .spacing(6)
    .align_y(Alignment::Center);

    let mut col = column![header, top, body_editor, counter].spacing(12).height(Length::Fill);
    if read_only {
        if let Some(p) = post {
            let demo = app.demo();
            let stats = dir_row(
                rtl,
                vec![
                    stat_inline(Icon::Eye, if demo { i.num(p.views) } else { "—".into() }, app.t("studio.views")),
                    stat_inline(Icon::Message, if demo || p.status == PostStatus::Published { i.num(p.reactions) } else { "—".into() }, app.t("studio.reactions")),
                    stat_inline(Icon::Forward, if demo { i.num(p.shares) } else { "—".into() }, app.t("studio.shares")),
                ],
            )
            .spacing(20);
            col = col.push(stats);
            col = col.push(banner(Icon::Radio, app.t(match p.delivery_state.as_deref() { Some("uncertain") => "studio.uncertain", Some("sending") => "studio.sending", _ => "studio.published_note" }), "muted"));
        }
    } else {
        let status_chip = |st: PostStatus, key: &str| button(small(app.t(key))).padding(Padding::from([6, 12])).style(theme::chip(s.status == st)).on_press(Message::PostStatus(st));
        let when_ok = parse_when(&s.when).is_some();
        let when = text_input("2026-10-21 18:00", &s.when).on_input(Message::When).padding(Padding::from([7, 10])).style(theme::input).font(INTER).size(13.5).width(150);
        let quick = row![
            button(small("+1h")).padding(Padding::from([6, 9])).style(theme::secondary).on_press(Message::WhenQuick(1)),
            button(small("+1d")).padding(Padding::from([6, 9])).style(theme::secondary).on_press(Message::WhenQuick(24)),
            btn(Some(Icon::Zap), app.t("studio.best_slot"), theme::soft, Some(Message::WhenBest)),
        ]
        .spacing(6);
        let schedule = dir_row(
            rtl,
            vec![
                row![status_chip(PostStatus::Draft, "studio.save_draft_mode"), status_chip(PostStatus::Scheduled, "studio.schedule_mode")].spacing(6).into(),
                hspace(),
                icon_tinted(Icon::Clock, 14, if when_ok { "muted" } else { "danger" }),
                when.into(),
            ],
        )
        .spacing(8)
        .align_y(Alignment::Center);
        let timing = dir_row(rtl, vec![quick.into(), hspace(), small(i.tv("studio.timezone", &[("zone", &crate::api::zone())])).into()]).spacing(8).align_y(Alignment::Center);
        let category = text_input(&app.t("studio.category_placeholder"), &s.category).on_input(Message::Category).padding(Padding::from([7, 10])).style(theme::input).font(INTER).size(13.5).width(160);
        let mut actions = vec![dir_row(rtl, vec![icon_tinted(Icon::Hash, 14, "muted"), category.into()]).spacing(6).align_y(Alignment::Center).into(), hspace()];
        if post.is_some() {
            if s.confirm_delete {
                actions.push(small(app.t("studio.delete_q")).into());
                actions.push(btn(None, app.t("app.yes"), theme::danger, Some(Message::DeletePost)).into());
                actions.push(btn(None, app.t("app.cancel"), theme::ghost, Some(Message::AskDelete(false))).into());
            } else {
                actions.push(icon_btn(Icon::Trash, app.t("studio.delete"), Some(Message::AskDelete(true))));
            }
        }
        let save_label = app.t(if s.status == PostStatus::Draft { "studio.save_draft" } else { "studio.schedule" });
        actions.push(btn(Some(if s.saving { Icon::Loader } else if s.status == PostStatus::Draft { Icon::FileText } else { Icon::CalendarPlus }), save_label, theme::primary, (!s.saving && total <= LIMIT).then_some(Message::SavePost)).into());
        col = col.push(hr()).push(schedule).push(timing).push(dir_row(rtl, actions).spacing(10).align_y(Alignment::Center));
        if let Some(e) = &s.error {
            col = col.push(banner(Icon::Alert, e.clone(), "danger"));
        }
        if s.status == PostStatus::Scheduled {
            let note = if app.demo() { "studio.note_demo" } else if app.workspace.as_ref().is_some_and(|w| w.bot_enabled) { "studio.note_on" } else { "studio.note_off" };
            col = col.push(small(app.t(note)));
        }
    }
    card(col).width(Length::Fill).height(Length::Fill).into()
}

fn stat_inline<'a>(i: Icon, value: String, label: String) -> Element<'a, Message> {
    row![icon_tinted(i, 15, "muted"), strong(value), small(label)].spacing(6).align_y(Alignment::Center).into()
}

fn copilot(app: &App) -> Element<'_, Message> {
    let s = &app.studio;
    let rtl = app.rtl();
    let running = s.ai.running;
    let mut col = column![dir_row(rtl, vec![container(icon_tinted(Icon::Sparkles, 15, "accent")).center(28).style(theme::badge("accent")).into(), heading(app.t("ai.copilot"), 16).into()]).spacing(8).align_y(Alignment::Center)].spacing(12);
    if !app.ai_ready() {
        col = col.push(small(app.t("ai.unready_text"))).push(btn(Some(Icon::Key), app.t("ai.connect"), theme::primary, Some(Message::SettingsTab(crate::app::SettingsTab::Ai))).width(Length::Fill));
        return card(column![col, gap(6.0), preview(app)].spacing(14)).width(340).height(Length::Fill).into();
    }
    if let Some(c) = app.channel_by_id(&s.channel_id) {
        col = col.push(knows(app, c));
    }
    let brief = text_input(&app.t("ai.brief_placeholder"), &s.brief).on_input(Message::Brief).on_submit(Message::RunRewrite(Rewrite::Draft)).padding(9).style(theme::input).font(INTER).size(13.5);
    col = col.push(field(app.t("ai.brief"), brief, None)).push(btn(Some(Icon::Wand), app.t("ai.draft"), theme::primary, (!running && s.open).then_some(Message::RunRewrite(Rewrite::Draft))).width(Length::Fill));
    col = col.push(hr()).push(small(app.t("ai.quick")));
    let has_text = s.open && !s.body.text().trim().is_empty();
    let action = |r: Rewrite| button(small(app.t(r.key()))).padding(Padding::from([5, 10])).style(theme::chip(false)).on_press_maybe((has_text && !running).then_some(Message::RunRewrite(r)));
    let quick = Rewrite::QUICK;
    col = col.push(row(quick.iter().map(|r| action(*r).into())).spacing(6).wrap().vertical_spacing(6).align_x(start(rtl)));
    let tones: Vec<Choice> = TONES.iter().map(|t| Choice::new(*t, app.t(&format!("tone.{t}")))).collect();
    let langs: Vec<Choice> = LANGS.iter().map(|l| Choice::new(l.code, l.name)).collect();
    col = col.push(dir_row(rtl, vec![picker(tones, s.tone, |t| Message::Tone(TONES.iter().find(|x| **x == t).copied().unwrap_or(TONES[0]))), button(small(app.t("ai.tone"))).padding(Padding::from([7, 10])).style(theme::secondary).on_press_maybe((has_text && !running).then_some(Message::RunRewrite(Rewrite::Tone))).into()]).spacing(6).align_y(Alignment::Center));
    col = col.push(dir_row(rtl, vec![picker(langs, &s.target_lang, Message::TargetLang), button(small(app.t("ai.translate"))).padding(Padding::from([7, 10])).style(theme::secondary).on_press_maybe((has_text && !running).then_some(Message::RunRewrite(Rewrite::Translate))).into()]).spacing(6).align_y(Alignment::Center));
    if let Some(out) = ai_output(app, &s.ai, AiSlot::Studio, 260.0) {
        col = col.push(out);
        if !running && !s.ai.output.is_empty() {
            // The main action gets a full-width line; the others share the next. Long labels (Uzbek, Vietnamese) still fit the pane.
            let discard = btn(Some(Icon::X), app.t("ai.discard"), theme::ghost, Some(Message::ApplyAi(ApplyMode::Discard))).width(Length::Fill);
            let (main, second) = match s.last_rewrite {
                Some(Rewrite::Headlines) => (btn(Some(Icon::Type), app.t("ai.use_title"), theme::primary, Some(Message::ApplyAi(ApplyMode::Title))), None),
                Some(Rewrite::Hashtags) => (btn(Some(Icon::Hash), app.t("ai.add_below"), theme::primary, Some(Message::ApplyAi(ApplyMode::Append))), None),
                _ => (btn(Some(Icon::Check), app.t("ai.replace"), theme::primary, Some(Message::ApplyAi(ApplyMode::Replace))), Some(btn(None, app.t("ai.add_below"), theme::secondary, Some(Message::ApplyAi(ApplyMode::Append))).width(Length::Fill))),
            };
            let mut others: Vec<Element<Message>> = second.into_iter().map(Element::from).collect();
            others.push(discard.into());
            col = col.push(column![main.width(Length::Fill), dir_row(rtl, others).spacing(6)].spacing(6));
        }
    } else if !s.open {
        col = col.push(small(app.t("ai.open_post_first")));
    }
    card(scroll(column![col, hr(), preview(app)].spacing(14)).height(Length::Fill)).width(340).height(Length::Fill).into()
}

/// How the post will look in the channel.
fn preview(app: &App) -> Element<'_, Message> {
    let s = &app.studio;
    let rtl = app.rtl();
    let channel = app.channel_by_id(&s.channel_id);
    let mut content = s.body.text().trim_end().to_string();
    if let Some(sig) = channel.map(|c| c.signature.trim()).filter(|s| !s.is_empty()) {
        content.push_str("\n\n");
        content.push_str(sig);
    }
    let when = parse_when(&s.when).or_else(|| app.editing_post().and_then(|p| local(&p.scheduled_at)));
    let name = channel.map(|c| c.name.clone()).unwrap_or_default();
    let bubble = container(
        column![
            text(name).font(INTER_SEMIBOLD).size(13).style(|th: &iced::Theme| text::Style { color: Some(theme::tokens(th).accent) }),
            text(if content.is_empty() { app.t("studio.preview_empty") } else { content }).font(INTER).size(13.5).line_height(1.45),
            container(faint(when.map(|d| app.i18n.time(&d)).unwrap_or_default())).align_x(Alignment::End).width(Length::Fill),
        ]
        .spacing(4),
    )
    .padding(Padding::from([9, 12]))
    .style(theme::bubble)
    .width(Length::Fill);
    let avatar = channel.map(|c| channel_avatar(app, c, 30.0)).unwrap_or_else(|| initials_avatar("?", "?", 30.0));
    column![
        small(app.t("studio.preview")),
        container(dir_row(rtl, vec![container(avatar).align_y(Alignment::End).height(Length::Shrink).into(), bubble.into()]).spacing(8).align_y(Alignment::End)).padding(12).style(theme::chat_wall).width(Length::Fill),
    ]
    .spacing(8)
    .into()
}
