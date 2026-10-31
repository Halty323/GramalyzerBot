//! Gramalyzer Studio: a desktop client for a Gramalyzer backend, for planning, writing and analysing Telegram
//! channel content, with an AI copilot that works with any OpenAI-compatible provider.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod ai;
mod api;
mod app;
mod charts;
mod config;
mod i18n;
mod model;
mod pages;
mod prompts;
mod theme;
mod ui;
mod view;

use app::App;

fn main() -> iced::Result {
    iced::application(App::new, App::update, view::view)
        .title(App::title)
        .theme(|app: &App| theme::theme(app.dark))
        .subscription(App::subscription)
        .font(include_bytes!("../assets/fonts/Inter_400Regular.ttf").as_slice())
        .font(include_bytes!("../assets/fonts/Inter_600SemiBold.ttf").as_slice())
        .font(include_bytes!("../assets/fonts/Inter_700Bold.ttf").as_slice())
        .font(include_bytes!("../assets/fonts/NotoSansArabic_400Regular.ttf").as_slice())
        .font(include_bytes!("../assets/fonts/NotoSansArabic_600SemiBold.ttf").as_slice())
        .font(include_bytes!("../assets/fonts/NotoSansDevanagari_400Regular.ttf").as_slice())
        .font(include_bytes!("../assets/fonts/NotoSansDevanagari_600SemiBold.ttf").as_slice())
        .font(include_bytes!("../assets/fonts/lucide.ttf").as_slice())
        .default_font(ui::INTER)
        .window(iced::window::Settings {
            size: window_size(),
            min_size: Some(iced::Size::new(1180.0, 720.0)),
            // closing asks the server for one last channel refresh first (App::update, Message::CloseRequested)
            exit_on_close_request: false,
            ..Default::default()
        })
        .antialiasing(true)
        .run()
}

/// 1440×900, or `GRAMALYZER_STUDIO_SIZE` ("1920x1080"), which the Docker image sets to its virtual screen.
fn window_size() -> iced::Size {
    std::env::var("GRAMALYZER_STUDIO_SIZE")
        .ok()
        .and_then(|s| {
            let (w, h) = s.split_once('x')?;
            Some(iced::Size::new(w.trim().parse::<f32>().ok()?.max(1180.0), h.trim().parse::<f32>().ok()?.max(720.0)))
        })
        .unwrap_or(iced::Size::new(1440.0, 900.0))
}
