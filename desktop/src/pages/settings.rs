//! Settings: the server connection, the AI provider, the writing voice and each channel's posting settings.
use crate::ai::PRESETS;
use crate::app::{App, Message, SettingsTab};
use crate::i18n::LANGS;
use crate::pages::section;
use crate::theme;
use crate::ui::*;
use crate::view::{Choice, channel_avatar, picker};
use iced::widget::{button, column, container, row, text_editor, text_input, toggler};
use iced::{Alignment, Element, Length, Padding};

const ZONES: [&str; 17] = [
    "Europe/Kyiv", "Europe/Berlin", "Europe/London", "Europe/Moscow", "Europe/Istanbul", "Asia/Tehran", "Asia/Dubai", "Asia/Tashkent", "Asia/Kolkata", "Asia/Ho_Chi_Minh", "Asia/Jakarta", "Asia/Singapore", "Asia/Riyadh", "America/Sao_Paulo", "America/New_York", "America/Los_Angeles", "UTC",
];

pub fn view(app: &App) -> Element<'_, Message> {
    let rtl = app.rtl();
    let tab = app.settings.tab;
    let tabs = [(SettingsTab::Connection, "set.tab_connection", Icon::Server), (SettingsTab::Ai, "set.tab_ai", Icon::Sparkles), (SettingsTab::Writing, "set.tab_writing", Icon::PenLine), (SettingsTab::Channel, "set.tab_channel", Icon::Radio)];
    let side = column(tabs.iter().map(|(t, key, ic)| {
        button(dir_row(rtl, vec![icon(*ic, 16), strong(app.t(key)).size(13.5).into()]).spacing(10).align_y(Alignment::Center)).width(Length::Fill).padding(Padding::from([9, 12])).style(theme::nav(tab == *t)).on_press(Message::SettingsTab(*t)).into()
    }))
    .spacing(4)
    .width(220);
    let content = match tab {
        SettingsTab::Connection => connection(app),
        SettingsTab::Ai => ai(app),
        SettingsTab::Writing => writing(app),
        SettingsTab::Channel => channel(app),
    };
    dir_row(rtl, vec![side.into(), container(content).width(Length::Fill).into()]).spacing(18).into()
}

fn input<'a>(placeholder: &str, value: &'a str, on: fn(String) -> Message) -> text_input::TextInput<'a, Message> {
    text_input(placeholder, value).on_input(on).padding(10).style(theme::input).font(INTER).size(14)
}

fn connection(app: &App) -> Element<'_, Message> {
    let s = &app.settings;
    let status = match (app.demo(), app.workspace.as_ref().is_some_and(|w| w.bot_enabled)) {
        (true, _) => banner(Icon::Radio, app.t("set.conn_demo"), "accent"),
        (false, true) => banner(Icon::CheckCircle, app.t("set.conn_live"), "success"),
        (false, false) => banner(Icon::Radio, app.t("set.conn_bot_off"), "warning"),
    };
    card(
        column![
            section(app, app.t("set.conn_title"), Some(app.t("set.conn_text"))),
            status,
            field(app.t("login.server"), input("https://gramalyzer.example.com", &s.server, Message::Server), Some(app.t("login.server_hint"))),
            field(app.t("login.token"), input("••••••••", &s.token, Message::Token).secure(true), Some(app.t("set.token_hint"))),
            row![btn(Some(Icon::Check), app.t("set.save_connect"), theme::primary, Some(Message::SaveConnection))],
        ]
        .spacing(16)
        .max_width(640),
    )
    .width(Length::Fill)
    .into()
}

fn ai(app: &App) -> Element<'_, Message> {
    let s = &app.settings;
    let rtl = app.rtl();
    let preset = crate::ai::preset(&s.provider);
    let providers: Vec<Choice> = PRESETS.iter().map(|p| Choice::new(p.id, p.name)).collect();
    let mut model_row = vec![container(input("model-id", &s.model, Message::Model)).width(Length::Fill).into(), btn(Some(if s.loading_models { Icon::Loader } else { Icon::ListChecks }), app.t("set.load_models"), theme::secondary, (!s.loading_models && !s.base_url.trim().is_empty()).then_some(Message::LoadModels)).into()];
    if !s.models.is_empty() {
        let models: Vec<Choice> = s.models.iter().map(|m| Choice::new(m.clone(), m.clone())).collect();
        model_row.insert(1, picker(models, &s.model, Message::Model));
    }
    let key_input = input(if preset.needs_key { "sk-…" } else { "(not needed)" }, &s.api_key, Message::ApiKey).secure(!s.show_key);
    let mut key_row = vec![container(key_input).width(Length::Fill).into(), icon_btn(Icon::Eye, app.t(if s.show_key { "set.hide_key" } else { "set.show_key" }), Some(Message::ShowKey(!s.show_key)))];
    if !preset.key_url.is_empty() {
        key_row.push(btn(Some(Icon::External), app.t("set.get_key"), theme::soft, Some(Message::OpenUrl(preset.key_url.to_string()))).into());
    }
    let mut form = column![
        section(app, app.t("set.ai_title"), Some(app.t("set.ai_text"))),
        field(app.t("set.provider"), picker(providers, &s.provider, Message::Provider), Some(preset.free_tier.to_string())),
        field(app.t("set.base_url"), input("https://…/v1", &s.base_url, Message::BaseUrl), None),
        field(app.t("set.api_key"), dir_row(rtl, key_row).spacing(8).align_y(Alignment::Center), Some(app.t("set.key_hint"))),
        field(app.t("set.model"), dir_row(rtl, model_row).spacing(8).align_y(Alignment::Center), None),
        dir_row(rtl, vec![btn(Some(if s.testing { Icon::Loader } else { Icon::Zap }), app.t("set.test"), theme::secondary, (!s.testing).then_some(Message::TestAi)).into(), btn(Some(Icon::Check), app.t("set.save"), theme::primary, Some(Message::SaveAi)).into()]).spacing(10),
    ]
    .spacing(16);
    match &s.ai_test {
        Some(Ok(answer)) => form = form.push(banner(Icon::CheckCircle, app.i18n.tv("set.test_ok", &[("answer", answer.trim())]), "success")),
        Some(Err(e)) => form = form.push(banner(Icon::Alert, e.clone(), "danger")),
        None => {}
    }

    let mut free = column![section(app, app.t("set.free_title"), Some(app.t("set.free_text")))].spacing(10);
    for p in PRESETS.iter().filter(|p| p.id != "custom") {
        let mut r = vec![column![strong(p.name.to_string()).size(13.5), small(p.free_tier.to_string())].spacing(3).width(Length::Fill).into()];
        r.push(btn(None, app.t("set.use"), theme::secondary, Some(Message::Provider(p.id.to_string()))).into());
        if !p.key_url.is_empty() {
            r.push(icon_btn(Icon::External, app.t("set.get_key"), Some(Message::OpenUrl(p.key_url.to_string()))));
        }
        free = free.push(container(dir_row(rtl, r).spacing(10).align_y(Alignment::Center)).padding(12).style(theme::inset).width(Length::Fill));
    }
    free = free.push(small(app.t("set.free_note")));
    column![card(form.max_width(720)).width(Length::Fill), card(free).width(Length::Fill)].spacing(16).into()
}

fn writing(app: &App) -> Element<'_, Message> {
    let s = &app.settings;
    let mut langs = vec![Choice::new("", app.t("set.same_as_posts"))];
    langs.extend(LANGS.iter().map(|l| Choice::new(l.code, l.name)));
    card(
        column![
            section(app, app.t("set.writing_title"), Some(app.t("set.writing_text"))),
            field(app.t("set.voice"), text_editor(&s.brand_voice).placeholder(app.t("set.voice_placeholder")).on_action(Message::BrandVoice).padding(12).height(140).font(INTER).size(14).style(theme::editor), Some(app.t("set.voice_hint"))),
            field(app.t("set.content_lang"), picker(langs, &s.content_lang, Message::ContentLang), Some(app.t("set.content_lang_hint"))),
            row![btn(Some(Icon::Check), app.t("set.save"), theme::primary, Some(Message::SaveWriting))],
        ]
        .spacing(16)
        .max_width(720),
    )
    .width(Length::Fill)
    .into()
}

fn channel(app: &App) -> Element<'_, Message> {
    let s = &app.settings;
    let i = &app.i18n;
    let rtl = app.rtl();
    let Some(c) = app.focus_channel() else {
        return card(muted(app.t("studio.no_channels"))).width(Length::Fill).into();
    };
    let dirty = s.paused != c.paused || s.timezone != c.timezone || s.signature != c.signature;
    let mut zones: Vec<Choice> = ZONES.iter().map(|z| Choice::new(*z, *z)).collect();
    if !ZONES.contains(&c.timezone.as_str()) {
        zones.insert(0, Choice::new(c.timezone.clone(), c.timezone.clone()));
    }
    let toggle_row = |title: String, text: String, value: bool, on: Option<fn(bool) -> Message>| {
        dir_row(rtl, vec![column![strong(title), small(text)].spacing(2).width(Length::Fill).into(), toggler(value).on_toggle_maybe(on).size(20).into()]).spacing(12).align_y(Alignment::Center)
    };
    let mut channels = vec![];
    for ch in app.channels() {
        channels.push(Choice::new(ch.id.clone(), ch.name.clone()));
    }
    card(
        column![
            dir_row(rtl, vec![channel_avatar(app, c, 40.0), column![heading(c.name.clone(), 17), small(i.tv("set.config", &[("handle", &c.handle)]))].spacing(2).width(Length::Fill).into(), picker(channels, &c.id, |id| Message::SelectChannel(Some(id)))]).spacing(12).align_y(Alignment::Center),
            hr(),
            strong(app.t("set.posting")),
            toggle_row(app.t("set.pause"), app.t("set.pause_text"), s.paused, Some(Message::ChPaused)),
            dir_row(rtl, vec![column![strong(app.t("set.timezone")), small(app.t("set.timezone_text"))].spacing(2).width(Length::Fill).into(), picker(zones, &s.timezone, Message::ChTimezone)]).spacing(12).align_y(Alignment::Center),
            field(app.t("set.signature"), input(&app.t("set.signature_example"), &s.signature, Message::ChSignature), Some(i.tv("set.chars", &[("n", &i.num(s.signature.chars().count() as i64)), ("max", &i.num(200))]))),
            hr(),
            dir_row(rtl, vec![strong(app.t("set.housekeeping")).into(), badge(app.t("set.planned"), "muted")]).spacing(8).align_y(Alignment::Center),
            toggle_row(app.t("set.notify"), app.t("set.notify_text"), c.notifications, None),
            toggle_row(app.t("set.cleanup"), app.t("set.cleanup_text"), c.auto_delete, None),
            dir_row(rtl, vec![small(app.t(if dirty { "set.dirty" } else { "set.clean" })).into(), hspace(), btn(Some(if s.channel_saving { Icon::Loader } else { Icon::Check }), app.t("set.save"), theme::primary, (dirty && !s.channel_saving).then_some(Message::SaveChannel)).into()]).align_y(Alignment::Center),
        ]
        .spacing(14)
        .max_width(720),
    )
    .width(Length::Fill)
    .into()
}
