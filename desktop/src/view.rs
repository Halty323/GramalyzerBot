//! The window: sign-in and connection screens, then the sidebar, the top bar, the current page and the toast.
use crate::app::{App, Conn, Message, Page};
use crate::i18n::LANGS;
use crate::model::{Channel, PostStatus, TrackedSummary};
use crate::pages;
use crate::theme;
use crate::ui::*;
use iced::widget::{Space, button, center, column, container, image, opaque, pick_list, row, stack, text, text_input};
use iced::{Alignment, Element, Length, Padding};
use std::fmt;

/// An option in a pick list: what it stands for and what it shows.
#[derive(Debug, Clone, PartialEq)]
pub struct Choice {
    pub id: String,
    pub label: String,
}

impl Choice {
    pub fn new(id: impl Into<String>, label: impl Into<String>) -> Self {
        Self { id: id.into(), label: label.into() }
    }
}

impl fmt::Display for Choice {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.label)
    }
}

pub fn picker<'a>(options: Vec<Choice>, selected: &str, on: impl Fn(String) -> Message + 'a) -> Element<'a, Message> {
    let current = options.iter().find(|o| o.id == selected).cloned();
    pick_list(options, current, move |c: Choice| on(c.id)).font(INTER).text_size(13.5).padding(Padding::from([7, 10])).style(theme::pick).menu_style(theme::menu).into()
}

pub fn channel_avatar<'a>(app: &App, c: &Channel, size: f32) -> Element<'a, Message> {
    if let Some(h) = app.photo_url(c).and_then(|u| app.photos.get(&u)) {
        return image(h.clone()).width(size).height(size).border_radius(size / 2.0).into();
    }
    initials_avatar(&c.name, &c.id, size)
}

pub fn tracked_avatar<'a>(app: &App, s: &TrackedSummary, size: f32) -> Element<'a, Message> {
    if let Some(h) = app.tracked_photo(s).and_then(|u| app.photos.get(&u)) {
        return image(h.clone()).width(size).height(size).border_radius(size / 2.0).into();
    }
    initials_avatar(&s.title, &s.username, size)
}

pub fn view(app: &App) -> Element<'_, Message> {
    let content: Element<Message> = match &app.conn {
        Conn::Connecting => center(column![icon(crate::ui::Icon::Loader, 28), muted(app.t("app.connecting"))].spacing(12).align_x(Alignment::Center)).style(theme::page).into(),
        Conn::Login(err) => login(app, err.as_deref()),
        Conn::Failed(err) => failed(app, err),
        Conn::Ready => shell(app),
    };
    let mut layers = stack![content];
    if let Some((msg, _)) = &app.toast {
        let toast = container(
            row![icon_tinted(Icon::CheckCircle, 16, "success"), text(msg.clone()).font(INTER).size(13.5), button(icon(Icon::X, 13)).style(theme::ghost).padding(4).on_press(Message::DismissToast)]
                .spacing(10)
                .align_y(Alignment::Center),
        )
        .padding(Padding::from([10, 14]))
        .style(theme::card);
        layers = layers.push(container(opaque(toast)).width(Length::Fill).height(Length::Fill).align_x(Alignment::Center).align_y(Alignment::End).padding(24));
    }
    layers.into()
}

fn brand<'a>() -> Element<'a, Message> {
    row![
        container(icon_tinted(Icon::Send, 18, "accent")).center(34).style(theme::badge("accent")),
        column![heading("Gramalyzer", 17), faint("STUDIO")].spacing(0),
    ]
    .spacing(10)
    .align_y(Alignment::Center)
    .into()
}

fn login<'a>(app: &'a App, err: Option<&str>) -> Element<'a, Message> {
    let mut form = column![
        brand(),
        gap(6.0),
        heading(app.t("login.title"), 22),
        muted(app.t("login.text")),
        field(app.t("login.server"), text_input("https://gramalyzer.example.com", &app.login_server).on_input(Message::LoginServer).padding(10).style(theme::input).font(INTER), Some(app.t("login.server_hint"))),
        field(app.t("login.token"), text_input("••••••••", &app.login_token).secure(true).on_input(Message::LoginToken).on_submit(Message::LoginSubmit).padding(10).style(theme::input).font(INTER), None),
        btn(Some(Icon::ChevronRight), app.t("login.submit"), theme::primary, Some(Message::LoginSubmit)).width(Length::Fill),
    ]
    .spacing(14)
    .width(400);
    if let Some(e) = err {
        form = form.push(banner(Icon::Alert, e.to_string(), "danger"));
    }
    center(card(form).padding(28)).style(theme::page).into()
}

fn failed<'a>(app: &'a App, err: &'a str) -> Element<'a, Message> {
    let body = column![
        brand(),
        gap(4.0),
        heading(app.t("app.offline_title"), 20),
        muted(app.i18n.tv("app.offline_text", &[("server", &app.config.server)])),
        banner(Icon::Alert, err.to_string(), "danger"),
        field(app.t("login.server"), text_input("http://localhost:8080", &app.login_server).on_input(Message::LoginServer).on_submit(Message::LoginSubmit).padding(10).style(theme::input).font(INTER), None),
        row![btn(Some(Icon::Refresh), app.t("app.retry"), theme::primary, Some(Message::LoginSubmit))].spacing(10),
    ]
    .spacing(14)
    .width(440);
    center(card(body).padding(28)).style(theme::page).into()
}

fn shell(app: &App) -> Element<'_, Message> {
    let side = sidebar(app);
    let main = column![topbar(app), page(app)].width(Length::Fill).height(Length::Fill);
    let main = container(main).style(theme::page).width(Length::Fill).height(Length::Fill);
    dir_row(app.rtl(), vec![side, main.into()]).height(Length::Fill).into()
}

fn sidebar(app: &App) -> Element<'_, Message> {
    let rtl = app.rtl();
    let posts = app.posts();
    let mut nav = column![].spacing(2);
    for (i, p) in Page::ALL.iter().enumerate() {
        if i == 4 || i == 5 || i == 7 {
            nav = nav.push(gap(8.0));
        }
        let ic = match p {
            Page::Dashboard => Icon::Dashboard,
            Page::Studio => Icon::PenLine,
            Page::Planner => Icon::Calendar,
            Page::Insights => Icon::Chart,
            Page::Assistant => Icon::Sparkles,
            Page::Viewer => Icon::Messages,
            Page::Watchlist => Icon::Telescope,
            Page::Channels => Icon::Layers,
            Page::Settings => Icon::Settings,
        };
        let count = match p {
            Page::Planner => Some(posts.iter().filter(|x| x.status == PostStatus::Scheduled).count()),
            Page::Studio => Some(posts.iter().filter(|x| x.status == PostStatus::Draft).count()),
            Page::Watchlist => app.tracked.as_ref().map(Vec::len),
            _ => None,
        };
        let mut items: Vec<Element<Message>> = vec![icon(ic, 17), text(app.t(p.key())).font(INTER_SEMIBOLD).size(13.5).into(), hspace()];
        if let Some(n) = count.filter(|n| *n > 0) {
            items.push(container(text(n.to_string()).font(INTER_SEMIBOLD).size(11)).padding(Padding::from([1, 7])).style(theme::badge("muted")).into());
        }
        nav = nav.push(button(dir_row(rtl, items).spacing(11).align_y(Alignment::Center)).width(Length::Fill).padding(Padding::from([8, 10])).style(theme::nav(app.page == *p)).on_press(Message::Navigate(*p)));
    }

    let mut channels = column![section_label(app.t("side.channels"), app.channels().len())].spacing(2);
    for c in app.channels() {
        let selected = app.channel.as_deref() == Some(&c.id);
        let mut items = vec![channel_avatar(app, c, 22.0), text(c.name.clone()).font(INTER).size(13).width(Length::Fill).into()];
        if c.paused {
            items.push(icon_tinted(Icon::Pause, 12, "warning"));
        }
        channels = channels.push(button(dir_row(rtl, items).spacing(9).align_y(Alignment::Center)).width(Length::Fill).padding(Padding::from([6, 10])).style(theme::list_item(selected)).on_press(Message::SelectChannel(if selected { None } else { Some(c.id.clone()) })));
    }

    let tracked = app.tracked.as_deref().unwrap_or(&[]);
    let mut watching = column![section_label(app.t("side.watching"), tracked.len())].spacing(2);
    let mut top: Vec<&TrackedSummary> = tracked.iter().collect();
    top.sort_by_key(|s| std::cmp::Reverse(s.subscribers.unwrap_or(0)));
    for s in top.into_iter().take(5) {
        let mut items = vec![tracked_avatar(app, s, 22.0), text(s.title.clone()).font(INTER).size(13).width(Length::Fill).into()];
        if let Some(c) = s.change7 {
            items.push(tinted(app.i18n.signed(c), if c >= 0 { "success" } else { "danger" }).size(11).into());
        }
        watching = watching.push(button(dir_row(rtl, items).spacing(9).align_y(Alignment::Center)).width(Length::Fill).padding(Padding::from([6, 10])).style(theme::list_item(false)).on_press(Message::View(crate::app::Source::Public(s.username.clone()))));
    }
    watching = watching.push(button(dir_row(rtl, vec![icon(Icon::Plus, 13), small(app.t("side.track")).into()]).spacing(9).align_y(Alignment::Center)).padding(Padding::from([6, 10])).style(theme::ghost).on_press(Message::Navigate(Page::Watchlist)));

    let ai = &app.config.ai;
    let ai_line: Element<Message> = if app.ai_ready() {
        dir_row(rtl, vec![icon_tinted(Icon::Sparkles, 14, "accent"), column![small(crate::ai::preset(&ai.provider).name.to_string()), faint(ai.model.clone())].spacing(1).into()]).spacing(9).align_y(Alignment::Center).into()
    } else {
        button(dir_row(rtl, vec![icon_tinted(Icon::Key, 14, "warning"), small(app.t("side.ai_setup")).into()]).spacing(9).align_y(Alignment::Center)).padding(0).style(theme::ghost).on_press(Message::SettingsTab(crate::app::SettingsTab::Ai)).into()
    };
    let status = if app.demo() { ("muted", app.t("side.demo")) } else if app.workspace.as_ref().is_some_and(|w| w.bot_enabled) { ("success", app.t("side.live")) } else { ("warning", app.t("side.bot_off")) };
    let footer = column![
        hr(),
        container(ai_line).padding(Padding::from([4, 10])),
        container(dir_row(rtl, vec![icon_tinted(Icon::Radio, 13, status.0), small(status.1).into()]).spacing(9).align_y(Alignment::Center)).padding(Padding::from([0, 10])),
    ]
    .spacing(10);

    let body = column![container(brand()).padding(Padding::from([4, 8])), gap(10.0), nav, gap(12.0), channels, gap(8.0), watching].spacing(4);
    container(column![scroll(body).height(Length::Fill), footer].spacing(8)).padding(14).width(236).height(Length::Fill).style(theme::sidebar).into()
}

fn section_label<'a>(label: String, n: usize) -> Element<'a, Message> {
    container(row![eyebrow(label).style(|th: &iced::Theme| text::Style { color: Some(theme::tokens(th).faint) }), hspace(), faint(n.to_string())]).padding(Padding::from([6, 10])).into()
}

fn topbar(app: &App) -> Element<'_, Message> {
    let rtl = app.rtl();
    let mut channels = vec![Choice::new("", app.t("filter.all"))];
    channels.extend(app.channels().iter().map(|c| Choice::new(c.id.clone(), c.name.clone())));
    let langs: Vec<Choice> = LANGS.iter().map(|l| Choice::new(l.code, l.name)).collect();
    let mut items: Vec<Element<Message>> = vec![
        column![faint(app.t("top.workspace")), heading(app.t(app.page.key()), 19)].spacing(1).into(),
        hspace(),
        row![icon_tinted(Icon::Layers, 14, "muted"), picker(channels, app.channel.as_deref().unwrap_or(""), |id| Message::SelectChannel(if id.is_empty() { None } else { Some(id) }))].spacing(6).align_y(Alignment::Center).into(),
        row![icon_tinted(Icon::Languages, 14, "muted"), picker(langs, app.i18n.lang().code, Message::Language)].spacing(6).align_y(Alignment::Center).into(),
        icon_btn(if app.dark { Icon::Sun } else { Icon::Moon }, app.t(if app.dark { "top.light" } else { "top.dark" }), Some(Message::ToggleTheme)),
        icon_btn(Icon::Refresh, app.t("top.refresh"), Some(Message::Reload)),
        btn(Some(Icon::Plus), app.t("act.new_post"), theme::primary, Some(Message::NewPost(None))).into(),
    ];
    if app.page == Page::Studio {
        items.insert(items.len() - 1, icon_btn(Icon::Download, app.t("act.export"), Some(Message::Export)));
    }
    let bar = dir_row(rtl, items).spacing(10).align_y(Alignment::Center);
    column![container(bar).padding(Padding::from([14, 24])), hr()].into()
}

fn page(app: &App) -> Element<'_, Message> {
    let mut body = column![].spacing(14);
    if let Some(e) = &app.error {
        body = body.push(
            container(row![icon(Icon::Alert, 16), text(e.clone()).font(INTER).size(13.5).width(Length::Fill), button(icon(Icon::X, 13)).style(theme::ghost).padding(4).on_press(Message::DismissError)].spacing(10).align_y(Alignment::Center))
                .padding(Padding::from([8, 14]))
                .style(theme::banner("danger")),
        );
    }
    if app.channels().is_empty() && !matches!(app.page, Page::Watchlist | Page::Viewer | Page::Settings | Page::Assistant) {
        body = body.push(banner(Icon::Radio, format!("{} {}", app.t("connect.title"), app.t("connect.text")), "accent"));
    }
    // Pages that manage their own scrolling (split panes, chat) fill the window; the rest scroll as one.
    let fixed = matches!(app.page, Page::Studio | Page::Assistant | Page::Viewer);
    let content: Element<Message> = match app.page {
        Page::Dashboard => pages::dashboard::view(app),
        Page::Studio => pages::studio::view(app),
        Page::Planner => pages::planner::view(app),
        Page::Insights => pages::insights::view(app),
        Page::Assistant => pages::assistant::view(app),
        Page::Viewer => pages::viewer::view(app),
        Page::Watchlist => pages::watchlist::view(app),
        Page::Channels => pages::channels::view(app),
        Page::Settings => pages::settings::view(app),
    };
    if fixed {
        container(body.push(content).height(Length::Fill)).padding(Padding::from([18, 24])).height(Length::Fill).into()
    } else {
        scroll(container(body.push(content).push(Space::new().height(12))).padding(Padding::from([18, 24]))).height(Length::Fill).into()
    }
}
