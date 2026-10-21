//! The application: its state, the messages that change it, and `update`. Views live in view.rs and pages/.
use crate::ai::{self, AiConfig, ChatMessage, PlannedPost};
use crate::api::{ApiError, Client};
use crate::config::Config;
use crate::i18n::{self, I18n};
use crate::model::*;
use crate::prompts;
use chrono::{DateTime, Duration as Days, Local, NaiveDateTime, TimeZone, Timelike};
use iced::widget::{image, text_editor};
use iced::{Subscription, Task, keyboard};
use std::collections::{HashMap, HashSet};
use std::time::{Duration, Instant};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Page {
    Dashboard,
    Studio,
    Planner,
    Insights,
    Assistant,
    Viewer,
    Watchlist,
    Channels,
    Settings,
}

impl Page {
    pub const ALL: [Page; 9] = [Page::Dashboard, Page::Studio, Page::Planner, Page::Insights, Page::Assistant, Page::Viewer, Page::Watchlist, Page::Channels, Page::Settings];
    pub fn key(self) -> &'static str {
        match self {
            Page::Dashboard => "nav.dashboard",
            Page::Studio => "nav.studio",
            Page::Planner => "nav.planner",
            Page::Insights => "nav.insights",
            Page::Assistant => "nav.assistant",
            Page::Viewer => "nav.viewer",
            Page::Watchlist => "nav.watchlist",
            Page::Channels => "nav.channels",
            Page::Settings => "nav.settings",
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum Conn {
    Connecting,
    /// The server wants a token (or the one saved was refused).
    Login(Option<String>),
    Ready,
    /// The server could not be reached; the message says why.
    Failed(String),
}

/// What the Studio's AI panel can do to the post being edited.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Rewrite {
    Draft,
    Improve,
    Shorten,
    Expand,
    Hook,
    Grammar,
    Hashtags,
    Headlines,
    Translate,
    Tone,
}

impl Rewrite {
    pub const QUICK: [Rewrite; 7] = [Rewrite::Improve, Rewrite::Hook, Rewrite::Shorten, Rewrite::Expand, Rewrite::Grammar, Rewrite::Hashtags, Rewrite::Headlines];
    pub fn key(self) -> &'static str {
        match self {
            Rewrite::Draft => "ai.draft",
            Rewrite::Improve => "ai.improve",
            Rewrite::Shorten => "ai.shorten",
            Rewrite::Expand => "ai.expand",
            Rewrite::Hook => "ai.hook",
            Rewrite::Grammar => "ai.grammar",
            Rewrite::Hashtags => "ai.hashtags",
            Rewrite::Headlines => "ai.headlines",
            Rewrite::Translate => "ai.translate",
            Rewrite::Tone => "ai.tone",
        }
    }
}

pub const TONES: [&str; 6] = ["friendly", "expert", "bold", "playful", "formal", "inspiring"];

/// Which AI request a stream of text belongs to, so pieces land in the right panel.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AiSlot {
    Studio,
    Assistant,
    Insights,
    Viewer,
    Watchlist,
}

#[derive(Debug, Default)]
pub struct AiRun {
    pub running: bool,
    pub output: String,
    pub error: Option<String>,
    /// Bumped on every new request so a cancelled stream's late pieces are dropped.
    pub generation: u64,
}

impl AiRun {
    fn start(&mut self) -> u64 {
        self.generation += 1;
        self.running = true;
        self.output.clear();
        self.error = None;
        self.generation
    }
}

#[derive(Debug)]
pub struct Studio {
    pub filter: Option<PostStatus>,
    pub search: String,
    /// The post open in the editor: Some(id) for a saved one, None for a new one; `open` says whether the editor shows at all.
    pub editing: Option<String>,
    pub open: bool,
    pub title: String,
    pub body: text_editor::Content,
    pub channel_id: String,
    pub status: PostStatus,
    pub when: String,
    pub category: String,
    pub saving: bool,
    pub error: Option<String>,
    pub confirm_delete: bool,
    pub dirty: bool,
    pub brief: String,
    pub tone: &'static str,
    pub target_lang: String,
    pub ai: AiRun,
    pub last_rewrite: Option<Rewrite>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Idea {
    pub post: PlannedPost,
    pub at: DateTime<Local>,
    pub keep: bool,
}

#[derive(Debug, Default)]
pub struct Planner {
    pub week: i64,
    pub panel: bool,
    pub count: usize,
    pub brief: String,
    pub running: bool,
    pub error: Option<String>,
    pub ideas: Vec<Idea>,
    pub creating: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Source {
    Mine(String),
    Public(String),
}

#[derive(Debug, Clone, PartialEq)]
pub enum Loaded {
    Feed(ChannelFeed),
    Detail(TrackedDetail),
}

#[derive(Debug, Default)]
pub struct Viewer {
    pub source: Option<Source>,
    pub loaded: Option<Loaded>,
    pub loading: bool,
    pub error: Option<ApiError>,
    pub input: String,
    pub scheduled: bool,
    pub ai: AiRun,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Sort {
    Title,
    Subscribers,
    Change,
    Views,
    Er,
    PerDay,
    Last,
}

#[derive(Debug)]
pub struct Watch {
    pub input: String,
    pub adding: bool,
    pub error: Option<String>,
    pub sort: Sort,
    pub desc: bool,
    pub removing: Option<String>,
    pub refreshing: Option<String>,
    pub ai: AiRun,
}

#[derive(Debug)]
pub struct Assistant {
    pub history: Vec<ChatMessage>,
    pub input: text_editor::Content,
    pub ai: AiRun,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SettingsTab {
    Connection,
    Ai,
    Writing,
    Channel,
}

#[derive(Debug)]
pub struct SettingsForm {
    pub tab: SettingsTab,
    pub server: String,
    pub token: String,
    pub provider: String,
    pub base_url: String,
    pub api_key: String,
    pub model: String,
    pub models: Vec<String>,
    pub loading_models: bool,
    pub ai_test: Option<Result<String, String>>,
    pub testing: bool,
    pub brand_voice: text_editor::Content,
    pub content_lang: String,
    pub show_key: bool,
    /// The selected channel's settings being edited.
    pub paused: bool,
    pub timezone: String,
    pub signature: String,
    pub channel_saving: bool,
}

pub struct App {
    pub config: Config,
    pub i18n: I18n,
    pub dark: bool,
    pub client: Client,
    pub conn: Conn,
    pub workspace: Option<Workspace>,
    pub tracked: Option<Vec<TrackedSummary>>,
    pub insights: HashMap<String, Result<Insights, ApiError>>,
    pub photos: HashMap<String, image::Handle>,
    requested_photos: HashSet<String>,
    pub page: Page,
    /// The channel the pages focus on; None = all channels.
    pub channel: Option<String>,
    pub period: i64,
    pub toast: Option<(String, Instant)>,
    pub error: Option<String>,
    pub login_server: String,
    pub login_token: String,
    pub studio: Studio,
    pub planner: Planner,
    pub viewer: Viewer,
    pub watch: Watch,
    pub assistant: Assistant,
    pub settings: SettingsForm,
    pub insights_ai: AiRun,
}

#[derive(Debug, Clone)]
pub enum Message {
    Tick,
    ToastTick,
    Workspace(Result<Workspace, ApiError>),
    Tracked(Result<Vec<TrackedSummary>, ApiError>),
    InsightsLoaded(String, Result<Insights, ApiError>),
    Photo(String, Option<Vec<u8>>),
    Navigate(Page),
    SelectChannel(Option<String>),
    Period(i64),
    Reload,
    DismissError,
    DismissToast,
    ToggleTheme,
    Language(String),
    OpenUrl(String),
    Copy(String),
    Saved(Result<(), String>),
    Key(keyboard::Key, keyboard::Modifiers),
    Export,
    Exported(Result<Option<String>, String>),

    LoginServer(String),
    LoginToken(String),
    LoginSubmit,

    // Studio
    StudioFilter(Option<PostStatus>),
    StudioSearch(String),
    NewPost(Option<DateTime<Local>>),
    OpenPost(String),
    CloseEditor,
    Title(String),
    Body(text_editor::Action),
    PostChannel(String),
    PostStatus(PostStatus),
    When(String),
    WhenQuick(i64),
    WhenBest,
    Category(String),
    SavePost,
    PostSaved(Result<Post, ApiError>),
    AskDelete(bool),
    DeletePost,
    PostDeleted(Result<(), ApiError>),
    Brief(String),
    Tone(&'static str),
    TargetLang(String),
    RunRewrite(Rewrite),
    ApplyAi(ApplyMode),

    // AI streams
    AiPiece(AiSlot, u64, Result<String, String>),
    AiStop(AiSlot),

    // Planner
    Week(i64),
    PlanPanel(bool),
    PlanCount(usize),
    PlanBrief(String),
    PlanRun,
    PlanDone(Result<Vec<PlannedPost>, String>),
    PlanToggle(usize),
    PlanCreate,
    PlanCreated(Result<usize, ApiError>),

    // Insights
    InsightsAi,

    // Assistant
    AssistantInput(text_editor::Action),
    AssistantSend,
    AssistantPrompt(String),
    AssistantClear,
    AssistantToStudio(usize),

    // Viewer
    View(Source),
    ViewerInput(String),
    ViewerOpen,
    ViewerLoaded(Source, Result<Loaded, ApiError>),
    ViewerScheduled(bool),
    ViewerAi,

    // Watchlist
    WatchInput(String),
    WatchAdd,
    WatchAdded(Result<TrackedDetail, ApiError>),
    WatchSort(Sort),
    WatchAskRemove(Option<String>),
    WatchRemove(String),
    WatchRemoved(String, Result<(), ApiError>),
    WatchRefresh(String),
    WatchRefreshed(Result<TrackedDetail, ApiError>),
    WatchAi,
    Track(String),

    // Channels
    TogglePause(String),
    ChannelSaved(Result<Channel, ApiError>),
    Configure(String),

    // Settings
    SettingsTab(SettingsTab),
    Server(String),
    Token(String),
    SaveConnection,
    Provider(String),
    BaseUrl(String),
    ApiKey(String),
    ShowKey(bool),
    Model(String),
    LoadModels,
    Models(Result<Vec<String>, String>),
    TestAi,
    AiTested(Result<String, String>),
    SaveAi,
    BrandVoice(text_editor::Action),
    ContentLang(String),
    SaveWriting,
    ChPaused(bool),
    ChTimezone(String),
    ChSignature(String),
    SaveChannel,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ApplyMode {
    Replace,
    Append,
    Title,
    Discard,
}

const FORMAT: &str = "%Y-%m-%d %H:%M";

pub fn local(iso: &str) -> Option<DateTime<Local>> {
    DateTime::parse_from_rfc3339(iso).ok().map(|d| d.with_timezone(&Local))
}

pub fn parse_when(s: &str) -> Option<DateTime<Local>> {
    let n = NaiveDateTime::parse_from_str(s.trim(), FORMAT).ok()?;
    Local.from_local_datetime(&n).earliest()
}

pub fn format_when(d: &DateTime<Local>) -> String {
    d.format(FORMAT).to_string()
}

fn tomorrow_at(hour: u32) -> DateTime<Local> {
    let d = Local::now() + Days::days(1);
    d.with_hour(hour).and_then(|d| d.with_minute(0)).and_then(|d| d.with_second(0)).unwrap_or(d)
}

impl App {
    pub fn new() -> (Self, Task<Message>) {
        let config = Config::load();
        let lang = if config.lang.is_empty() { i18n::detect().to_string() } else { config.lang.clone() };
        let dark = config.dark.unwrap_or(false);
        let client = Client::new(&config.server, &config.token);
        let s = &config;
        let settings = SettingsForm {
            tab: SettingsTab::Connection,
            server: s.server.clone(),
            token: s.token.clone(),
            provider: s.ai.provider.clone(),
            base_url: s.ai.base_url.clone(),
            api_key: s.ai.api_key.clone(),
            model: s.ai.model.clone(),
            models: vec![],
            loading_models: false,
            ai_test: None,
            testing: false,
            brand_voice: text_editor::Content::with_text(&s.brand_voice),
            content_lang: s.content_lang.clone(),
            show_key: false,
            paused: false,
            timezone: "UTC".into(),
            signature: String::new(),
            channel_saving: false,
        };
        let app = Self {
            i18n: I18n::new(&lang),
            dark,
            client: client.clone(),
            conn: Conn::Connecting,
            workspace: None,
            tracked: None,
            insights: HashMap::new(),
            photos: HashMap::new(),
            requested_photos: HashSet::new(),
            page: Page::Dashboard,
            channel: None,
            period: 28,
            toast: None,
            error: None,
            login_server: config.server.clone(),
            login_token: String::new(),
            studio: Studio {
                filter: None,
                search: String::new(),
                editing: None,
                open: false,
                title: String::new(),
                body: text_editor::Content::new(),
                channel_id: String::new(),
                status: PostStatus::Draft,
                when: format_when(&tomorrow_at(10)),
                category: String::new(),
                saving: false,
                error: None,
                confirm_delete: false,
                dirty: false,
                brief: String::new(),
                tone: TONES[0],
                target_lang: if lang == "en" { "uk".into() } else { "en".into() },
                ai: AiRun::default(),
                last_rewrite: None,
            },
            planner: Planner { count: 5, ..Default::default() },
            viewer: Viewer::default(),
            watch: Watch { input: String::new(), adding: false, error: None, sort: Sort::Subscribers, desc: true, removing: None, refreshing: None, ai: AiRun::default() },
            assistant: Assistant { history: vec![], input: text_editor::Content::new(), ai: AiRun::default() },
            settings,
            insights_ai: AiRun::default(),
            config,
        };
        (app, Task::perform(client.workspace(), Message::Workspace))
    }

    pub fn title(&self) -> String {
        format!("Gramalyzer Studio — {}", self.i18n.t(self.page.key()))
    }

    pub fn t(&self, key: &str) -> String {
        self.i18n.t(key)
    }

    pub fn rtl(&self) -> bool {
        self.i18n.rtl()
    }

    pub fn channels(&self) -> &[Channel] {
        self.workspace.as_ref().map(|w| w.channels.as_slice()).unwrap_or(&[])
    }

    pub fn posts(&self) -> &[Post] {
        self.workspace.as_ref().map(|w| w.posts.as_slice()).unwrap_or(&[])
    }

    pub fn demo(&self) -> bool {
        self.workspace.as_ref().is_some_and(|w| w.demo)
    }

    pub fn channel_by_id(&self, id: &str) -> Option<&Channel> {
        self.channels().iter().find(|c| c.id == id)
    }

    /// The channel a single-channel page shows: the selected one, else the first.
    pub fn focus_channel(&self) -> Option<&Channel> {
        self.channel.as_deref().and_then(|id| self.channel_by_id(id)).or_else(|| self.channels().first())
    }

    pub fn photo_url(&self, c: &Channel) -> Option<String> {
        c.telegram_chat_id.map(|_| self.client.url(&format!("/api/channels/{}/photo", c.id)))
    }

    pub fn tracked_photo(&self, s: &TrackedSummary) -> Option<String> {
        s.photo_url.as_ref().map(|p| self.client.url(p))
    }

    pub fn ai_ready(&self) -> bool {
        self.config.ai.ready()
    }

    fn toast(&mut self, text: String) {
        self.toast = Some((text, Instant::now()));
    }

    pub fn api_error(&self, e: &ApiError) -> String {
        if let Some(code) = &e.code {
            let key = format!("err.{code}");
            if self.i18n.has(&key) {
                return self.t(&key);
            }
        }
        if e.status == 0 {
            return self.i18n.tv("err.network", &[("error", &e.message)]);
        }
        if e.message.is_empty() { self.i18n.tv("err.status", &[("status", &e.status.to_string())]) } else { e.message.clone() }
    }

    fn save_config(&self) -> Task<Message> {
        Task::perform(self.config.clone().save(), Message::Saved)
    }

    fn reload(&self) -> Task<Message> {
        Task::perform(self.client.clone().workspace(), Message::Workspace)
    }

    fn load_insights(&mut self, id: &str, force: bool) -> Task<Message> {
        if !force && self.insights.contains_key(id) {
            return Task::none();
        }
        let id = id.to_string();
        Task::perform(self.client.clone().insights(id.clone()), move |r| Message::InsightsLoaded(id.clone(), r))
    }

    fn want_photo(&mut self, url: Option<String>) -> Task<Message> {
        let Some(url) = url else { return Task::none() };
        if !self.requested_photos.insert(url.clone()) {
            return Task::none();
        }
        Task::perform(self.client.clone().image(url.clone()), move |b| Message::Photo(url.clone(), b))
    }

    /// The insights a page needs right now.
    fn page_data(&mut self) -> Task<Message> {
        let ids: Vec<String> = match self.page {
            Page::Insights | Page::Planner | Page::Dashboard => self.focus_channel().map(|c| vec![c.id.clone()]).unwrap_or_default(),
            Page::Channels => self.channels().iter().map(|c| c.id.clone()).collect(),
            _ => vec![],
        };
        Task::batch(ids.iter().map(|id| self.load_insights(id, false)).collect::<Vec<_>>())
    }

    fn stream(&self, slot: AiSlot, generation: u64, messages: Vec<ChatMessage>, temperature: f32) -> Task<Message> {
        Task::run(ai::stream_chat(self.config.ai.clone(), messages, temperature), move |piece| Message::AiPiece(slot, generation, piece))
    }

    fn run_of(&mut self, slot: AiSlot) -> &mut AiRun {
        match slot {
            AiSlot::Studio => &mut self.studio.ai,
            AiSlot::Assistant => &mut self.assistant.ai,
            AiSlot::Insights => &mut self.insights_ai,
            AiSlot::Viewer => &mut self.viewer.ai,
            AiSlot::Watchlist => &mut self.watch.ai,
        }
    }

    /// The language AI content is written in: the one set in Writing settings, else the interface's.
    pub fn content_lang_name(&self) -> String {
        let code = if self.config.content_lang.is_empty() { self.i18n.lang().code } else { self.config.content_lang.as_str() };
        prompts::language_name(code).to_string()
    }

    fn context(&self) -> prompts::Context<'_> {
        let channel = self.focus_channel();
        prompts::Context {
            channel,
            insights: channel.and_then(|c| self.insights.get(&c.id)).and_then(|r| r.as_ref().ok()),
            posts: self.posts(),
            voice: &self.config.brand_voice,
            language: self.content_lang_name(),
        }
    }

    fn open_editor(&mut self, post: Option<&Post>, at: Option<DateTime<Local>>) {
        let s = &mut self.studio;
        s.open = true;
        s.error = None;
        s.confirm_delete = false;
        s.dirty = false;
        s.ai = AiRun { generation: s.ai.generation + 1, ..Default::default() };
        match post {
            Some(p) => {
                s.editing = Some(p.id.clone());
                s.title = p.title.clone();
                s.body = text_editor::Content::with_text(&p.body);
                s.channel_id = p.channel_id.clone();
                s.status = if p.status == PostStatus::Scheduled { PostStatus::Scheduled } else { p.status };
                s.when = local(&p.scheduled_at).map(|d| format_when(&d)).unwrap_or_default();
                s.category = p.category.clone();
            }
            None => {
                s.editing = None;
                s.title.clear();
                s.body = text_editor::Content::new();
                s.channel_id = self.channel.clone().or_else(|| self.workspace.as_ref().and_then(|w| w.channels.first().map(|c| c.id.clone()))).unwrap_or_default();
                s.status = if at.is_some() { PostStatus::Scheduled } else { PostStatus::Draft };
                s.when = format_when(&at.unwrap_or_else(|| tomorrow_at(10)));
                s.category = String::new();
            }
        }
        self.page = Page::Studio;
    }

    pub fn editing_post(&self) -> Option<&Post> {
        self.studio.editing.as_deref().and_then(|id| self.posts().iter().find(|p| p.id == id))
    }

    /// The next good slots for a channel: the bot's suggestions, else every day at 10:00 from tomorrow.
    pub fn slots(&self, channel_id: &str, count: usize) -> Vec<DateTime<Local>> {
        let mut out: Vec<DateTime<Local>> = self
            .insights
            .get(channel_id)
            .and_then(|r| r.as_ref().ok())
            .map(|i| i.suggestions.iter().filter_map(|s| local(&s.at)).filter(|d| *d > Local::now()).collect())
            .unwrap_or_default();
        out.sort();
        let mut day = 1;
        while out.len() < count {
            let candidate = tomorrow_at(10) + Days::days(day - 1);
            if !out.iter().any(|d| d.date_naive() == candidate.date_naive()) {
                out.push(candidate);
            }
            day += 1;
        }
        // Suggestions repeat weekly; spread extra ideas a week later at the same times.
        let mut extra = out.clone();
        while out.len() < count {
            for d in extra.iter_mut() {
                *d += Days::days(7);
            }
            out.extend(extra.iter().cloned());
        }
        out.sort();
        out.truncate(count);
        out
    }

    pub fn update(&mut self, message: Message) -> Task<Message> {
        match message {
            Message::Tick => {
                if self.conn == Conn::Ready {
                    return self.reload();
                }
            }
            Message::ToastTick => {
                if self.toast.as_ref().is_some_and(|(_, at)| at.elapsed() > Duration::from_secs(4)) {
                    self.toast = None;
                }
            }
            Message::Workspace(Ok(w)) => {
                let first = self.workspace.is_none();
                let photos: Vec<_> = w.channels.iter().map(|c| self.photo_url(c)).collect();
                if self.studio.channel_id.is_empty() {
                    self.studio.channel_id = w.channels.first().map(|c| c.id.clone()).unwrap_or_default();
                }
                self.workspace = Some(w);
                self.conn = Conn::Ready;
                let mut tasks: Vec<Task<Message>> = photos.into_iter().map(|u| self.want_photo(u)).collect();
                if first {
                    tasks.push(Task::perform(self.client.clone().tracked(), Message::Tracked));
                    tasks.push(self.page_data());
                    if let Some(c) = self.focus_channel() {
                        let c = c.clone();
                        self.fill_channel_form(&c);
                    }
                }
                return Task::batch(tasks);
            }
            Message::Workspace(Err(e)) => {
                if e.unauthorized() {
                    let msg = if self.config.token.is_empty() { None } else { Some(self.api_error(&e)) };
                    self.conn = Conn::Login(msg);
                } else if self.workspace.is_some() {
                    self.error = Some(self.api_error(&e));
                } else {
                    self.conn = Conn::Failed(self.api_error(&e));
                }
            }
            Message::Tracked(r) => {
                match r {
                    Ok(list) => {
                        let urls: Vec<_> = list.iter().map(|s| self.tracked_photo(s)).collect();
                        self.tracked = Some(list);
                        return Task::batch(urls.into_iter().map(|u| self.want_photo(u)).collect::<Vec<_>>());
                    }
                    Err(_) => {
                        if self.tracked.is_none() {
                            self.tracked = Some(vec![]);
                        }
                    }
                }
            }
            Message::InsightsLoaded(id, r) => {
                self.insights.insert(id, r);
            }
            Message::Photo(url, bytes) => {
                if let Some(b) = bytes {
                    self.photos.insert(url, image::Handle::from_bytes(b));
                }
            }
            Message::Navigate(p) => {
                self.page = p;
                self.error = None;
                if p == Page::Settings {
                    if let Some(c) = self.focus_channel().cloned() {
                        self.fill_channel_form(&c);
                    }
                }
                if p == Page::Viewer && self.viewer.source.is_none() {
                    if let Some(c) = self.focus_channel() {
                        let id = c.id.clone();
                        return self.update(Message::View(Source::Mine(id)));
                    }
                }
                return self.page_data();
            }
            Message::SelectChannel(id) => {
                self.channel = id;
                if let Some(c) = self.focus_channel().cloned() {
                    self.fill_channel_form(&c);
                }
                if self.page == Page::Viewer {
                    if let Some(id) = self.channel.clone() {
                        return self.update(Message::View(Source::Mine(id)));
                    }
                }
                return self.page_data();
            }
            Message::Period(d) => self.period = d,
            Message::Reload => {
                self.insights.clear();
                return Task::batch([self.reload(), Task::perform(self.client.clone().tracked(), Message::Tracked)]).chain(self.page_data());
            }
            Message::DismissError => self.error = None,
            Message::DismissToast => self.toast = None,
            Message::ToggleTheme => {
                self.dark = !self.dark;
                self.config.dark = Some(self.dark);
                return self.save_config();
            }
            Message::Language(code) => {
                self.i18n = I18n::new(&code);
                self.config.lang = code;
                return self.save_config();
            }
            Message::OpenUrl(url) => {
                if let Err(e) = open::that_detached(&url) {
                    self.error = Some(e.to_string());
                }
            }
            Message::Copy(text) => {
                self.toast(self.t("toast.copied"));
                return iced::clipboard::write(text);
            }
            Message::Saved(r) => {
                if let Err(e) = r {
                    self.error = Some(self.i18n.tv("err.save_config", &[("error", &e)]));
                }
            }
            Message::Key(key, mods) => {
                if mods.command() {
                    if let keyboard::Key::Character(c) = &key {
                        match c.as_str() {
                            "s" if self.page == Page::Studio && self.studio.open => return self.update(Message::SavePost),
                            "n" if self.conn == Conn::Ready => return self.update(Message::NewPost(None)),
                            "r" => return self.update(Message::Reload),
                            _ => {}
                        }
                    }
                }
            }
            Message::Export => {
                let csv = self.csv();
                let title = self.t("csv.save_title");
                return Task::perform(
                    async move {
                        let Some(file) = rfd::AsyncFileDialog::new().set_title(title).set_file_name("gramalyzer-posts.csv").add_filter("CSV", &["csv"]).save_file().await else {
                            return Ok(None);
                        };
                        tokio::fs::write(file.path(), csv).await.map_err(|e| e.to_string())?;
                        Ok(Some(file.path().display().to_string()))
                    },
                    Message::Exported,
                );
            }
            Message::Exported(r) => match r {
                Ok(Some(path)) => self.toast(self.i18n.tv("csv.done", &[("path", &path)])),
                Ok(None) => {}
                Err(e) => self.error = Some(e),
            },

            Message::LoginServer(s) => self.login_server = s,
            Message::LoginToken(s) => self.login_token = s,
            Message::LoginSubmit => {
                self.config.server = self.login_server.trim().to_string();
                if !self.login_token.trim().is_empty() {
                    self.config.token = self.login_token.trim().to_string();
                }
                self.settings.server = self.config.server.clone();
                self.settings.token = self.config.token.clone();
                self.client = Client::new(&self.config.server, &self.config.token);
                self.conn = Conn::Connecting;
                self.workspace = None;
                self.requested_photos.clear();
                return Task::batch([self.save_config(), self.reload()]);
            }

            // ---------- Studio ----------
            Message::StudioFilter(f) => self.studio.filter = f,
            Message::StudioSearch(s) => self.studio.search = s,
            Message::NewPost(at) => {
                if self.channels().is_empty() {
                    self.error = Some(self.t("studio.no_channels"));
                    return Task::none();
                }
                let from_viewer = match (&self.page, &self.viewer.source) {
                    (Page::Viewer, Some(Source::Mine(id))) => Some(id.clone()),
                    _ => None,
                };
                self.open_editor(None, at);
                if let Some(id) = from_viewer {
                    self.studio.channel_id = id;
                }
                if let Some(c) = self.channel_by_id(&self.studio.channel_id).map(|c| c.id.clone()) {
                    return self.load_insights(&c, false);
                }
            }
            Message::OpenPost(id) => {
                if let Some(p) = self.posts().iter().find(|p| p.id == id).cloned() {
                    self.open_editor(Some(&p), None);
                    return self.load_insights(&p.channel_id, false);
                }
            }
            Message::CloseEditor => {
                self.studio.open = false;
                self.studio.editing = None;
            }
            Message::Title(s) => {
                self.studio.title = s;
                self.studio.dirty = true;
            }
            Message::Body(action) => {
                if action.is_edit() {
                    self.studio.dirty = true;
                }
                self.studio.body.perform(action);
            }
            Message::PostChannel(id) => {
                self.studio.channel_id = id.clone();
                self.studio.dirty = true;
                return self.load_insights(&id, false);
            }
            Message::PostStatus(s) => {
                self.studio.status = s;
                self.studio.dirty = true;
            }
            Message::When(s) => {
                self.studio.when = s;
                self.studio.dirty = true;
            }
            Message::WhenQuick(hours) => {
                let base = parse_when(&self.studio.when).filter(|d| *d > Local::now()).unwrap_or_else(Local::now);
                let d = base + Days::hours(hours);
                self.studio.when = format_when(&d.with_minute(0).unwrap_or(d));
                self.studio.dirty = true;
            }
            Message::WhenBest => {
                let id = self.studio.channel_id.clone();
                if let Some(d) = self.slots(&id, 1).first() {
                    self.studio.when = format_when(d);
                    self.studio.status = PostStatus::Scheduled;
                    self.studio.dirty = true;
                }
            }
            Message::Category(s) => {
                self.studio.category = s;
                self.studio.dirty = true;
            }
            Message::SavePost => {
                let s = &self.studio;
                let editing = s.editing.clone();
                if s.saving || self.editing_post().is_some_and(Post::read_only) {
                    return Task::none();
                }
                let body = s.body.text().trim_end().to_string();
                let Some(at) = parse_when(&s.when) else {
                    self.studio.error = Some(self.t("studio.bad_date"));
                    return Task::none();
                };
                if s.title.trim().is_empty() || body.trim().is_empty() {
                    self.studio.error = Some(self.t("studio.need_text"));
                    return Task::none();
                }
                if s.status == PostStatus::Scheduled && at < Local::now() {
                    self.studio.error = Some(self.t("studio.in_past"));
                    return Task::none();
                }
                let input = PostInput {
                    channel_id: s.channel_id.clone(),
                    title: s.title.trim().to_string(),
                    body,
                    scheduled_at: at.to_utc().to_rfc3339_opts(chrono::SecondsFormat::Secs, true),
                    status: s.status,
                    category: if s.category.trim().is_empty() { "Community".into() } else { s.category.trim().to_string() },
                };
                self.studio.saving = true;
                self.studio.error = None;
                return Task::perform(self.client.clone().save_post(editing, input), Message::PostSaved);
            }
            Message::PostSaved(r) => {
                self.studio.saving = false;
                match r {
                    Ok(p) => {
                        self.toast(self.t(if p.status == PostStatus::Draft { "toast.draft_saved" } else { "toast.scheduled" }));
                        self.studio.editing = Some(p.id.clone());
                        self.studio.dirty = false;
                        if let Some(w) = self.workspace.as_mut() {
                            match w.posts.iter_mut().find(|x| x.id == p.id) {
                                Some(x) => *x = p,
                                None => w.posts.push(p),
                            }
                        }
                        return self.reload();
                    }
                    Err(e) => self.studio.error = Some(self.api_error(&e)),
                }
            }
            Message::AskDelete(b) => self.studio.confirm_delete = b,
            Message::DeletePost => {
                if let Some(id) = self.studio.editing.clone() {
                    return Task::perform(self.client.clone().delete_post(id), Message::PostDeleted);
                }
            }
            Message::PostDeleted(r) => match r {
                Ok(()) => {
                    if let (Some(w), Some(id)) = (self.workspace.as_mut(), self.studio.editing.as_ref()) {
                        w.posts.retain(|p| &p.id != id);
                    }
                    self.studio.open = false;
                    self.studio.editing = None;
                    self.toast(self.t("toast.deleted"));
                    return self.reload();
                }
                Err(e) => self.studio.error = Some(self.api_error(&e)),
            },
            Message::Brief(s) => self.studio.brief = s,
            Message::Tone(t) => self.studio.tone = t,
            Message::TargetLang(l) => self.studio.target_lang = l,
            Message::RunRewrite(kind) => {
                if !self.ai_ready() {
                    self.page = Page::Settings;
                    self.settings.tab = SettingsTab::Ai;
                    self.toast(self.t("ai.setup_first"));
                    return Task::none();
                }
                let body = self.studio.body.text();
                if kind != Rewrite::Draft && body.trim().is_empty() {
                    self.studio.ai.error = Some(self.t("ai.need_text"));
                    return Task::none();
                }
                if kind == Rewrite::Draft && self.studio.brief.trim().is_empty() {
                    self.studio.ai.error = Some(self.t("ai.need_brief"));
                    return Task::none();
                }
                let channel = self.channel_by_id(&self.studio.channel_id);
                let ctx = prompts::Context {
                    channel,
                    insights: channel.and_then(|c| self.insights.get(&c.id)).and_then(|r| r.as_ref().ok()),
                    posts: self.posts(),
                    voice: &self.config.brand_voice,
                    language: self.content_lang_name(),
                };
                let messages = prompts::rewrite(&ctx, kind, &self.studio.title, &body, &self.studio.brief, self.studio.tone, prompts::language_name(&self.studio.target_lang));
                self.studio.last_rewrite = Some(kind);
                let g = self.studio.ai.start();
                return self.stream(AiSlot::Studio, g, messages, if kind == Rewrite::Grammar { 0.2 } else { 0.8 });
            }
            Message::ApplyAi(mode) => {
                let out = self.studio.ai.output.trim().to_string();
                match mode {
                    ApplyMode::Replace => {
                        self.studio.body = text_editor::Content::with_text(&out);
                        self.studio.dirty = true;
                    }
                    ApplyMode::Append => {
                        let mut body = self.studio.body.text().trim_end().to_string();
                        body.push_str("\n\n");
                        body.push_str(&out);
                        self.studio.body = text_editor::Content::with_text(&body);
                        self.studio.dirty = true;
                    }
                    ApplyMode::Title => {
                        let line = out.lines().map(|l| l.trim().trim_start_matches(|c: char| c.is_ascii_digit() || ".)-•* ".contains(c)).trim_matches('"').to_string()).find(|l| !l.is_empty()).unwrap_or_default();
                        self.studio.title = line.chars().take(180).collect();
                        self.studio.dirty = true;
                    }
                    ApplyMode::Discard => {}
                }
                self.studio.ai.output.clear();
                self.studio.ai.error = None;
            }

            Message::AiPiece(slot, generation, piece) => {
                let run = self.run_of(slot);
                if run.generation != generation || !run.running {
                    return Task::none();
                }
                match piece {
                    Ok(p) if p.is_empty() => {
                        run.running = false;
                        if slot == AiSlot::Assistant {
                            let answer = std::mem::take(&mut self.assistant.ai.output);
                            self.assistant.history.push(ChatMessage::assistant(answer));
                        }
                    }
                    Ok(p) => run.output.push_str(&p),
                    Err(e) => {
                        run.running = false;
                        run.error = Some(e);
                        if slot == AiSlot::Assistant && !self.assistant.ai.output.is_empty() {
                            let answer = std::mem::take(&mut self.assistant.ai.output);
                            self.assistant.history.push(ChatMessage::assistant(answer));
                        }
                    }
                }
            }
            Message::AiStop(slot) => {
                let run = self.run_of(slot);
                run.generation += 1;
                run.running = false;
                if slot == AiSlot::Assistant && !self.assistant.ai.output.is_empty() {
                    let answer = std::mem::take(&mut self.assistant.ai.output);
                    self.assistant.history.push(ChatMessage::assistant(answer));
                }
            }

            // ---------- Planner ----------
            Message::Week(w) => self.planner.week = w,
            Message::PlanPanel(b) => {
                self.planner.panel = b;
                if b {
                    if let Some(c) = self.focus_channel().map(|c| c.id.clone()) {
                        return self.load_insights(&c, false);
                    }
                }
            }
            Message::PlanCount(n) => self.planner.count = n,
            Message::PlanBrief(s) => self.planner.brief = s,
            Message::PlanRun => {
                if !self.ai_ready() {
                    self.page = Page::Settings;
                    self.settings.tab = SettingsTab::Ai;
                    self.toast(self.t("ai.setup_first"));
                    return Task::none();
                }
                let Some(c) = self.focus_channel() else { return Task::none() };
                let _ = c;
                let messages = prompts::plan(&self.context(), self.planner.count, &self.planner.brief);
                self.planner.running = true;
                self.planner.error = None;
                self.planner.ideas.clear();
                return Task::perform(ai::complete(self.config.ai.clone(), messages, 0.9), |r| Message::PlanDone(r.and_then(|a| ai::parse_plan(&a))));
            }
            Message::PlanDone(r) => {
                self.planner.running = false;
                match r {
                    Ok(posts) => {
                        let id = self.focus_channel().map(|c| c.id.clone()).unwrap_or_default();
                        let slots = self.slots(&id, posts.len());
                        self.planner.ideas = posts.into_iter().zip(slots).map(|(post, at)| Idea { post, at, keep: true }).collect();
                    }
                    Err(e) => self.planner.error = Some(e),
                }
            }
            Message::PlanToggle(i) => {
                if let Some(idea) = self.planner.ideas.get_mut(i) {
                    idea.keep = !idea.keep;
                }
            }
            Message::PlanCreate => {
                let Some(channel) = self.focus_channel().map(|c| c.id.clone()) else { return Task::none() };
                let inputs: Vec<PostInput> = self
                    .planner
                    .ideas
                    .iter()
                    .filter(|i| i.keep)
                    .map(|i| PostInput {
                        channel_id: channel.clone(),
                        title: if i.post.title.trim().is_empty() { i.post.body.chars().take(60).collect() } else { i.post.title.chars().take(180).collect() },
                        body: i.post.body.chars().take(4096).collect(),
                        scheduled_at: i.at.to_utc().to_rfc3339_opts(chrono::SecondsFormat::Secs, true),
                        status: PostStatus::Draft,
                        category: if i.post.category.trim().is_empty() { "AI plan".into() } else { i.post.category.chars().take(40).collect() },
                    })
                    .collect();
                if inputs.is_empty() {
                    return Task::none();
                }
                self.planner.creating = true;
                let client = self.client.clone();
                return Task::perform(
                    async move {
                        let n = inputs.len();
                        for input in inputs {
                            client.clone().save_post(None, input).await?;
                        }
                        Ok(n)
                    },
                    Message::PlanCreated,
                );
            }
            Message::PlanCreated(r) => {
                self.planner.creating = false;
                match r {
                    Ok(n) => {
                        self.planner.ideas.clear();
                        self.planner.panel = false;
                        self.toast(self.i18n.tn("plan.created", n as i64));
                    }
                    Err(e) => self.planner.error = Some(self.api_error(&e)),
                }
                return self.reload();
            }

            Message::InsightsAi => {
                if !self.ai_ready() {
                    self.page = Page::Settings;
                    self.settings.tab = SettingsTab::Ai;
                    self.toast(self.t("ai.setup_first"));
                    return Task::none();
                }
                let messages = prompts::review(&self.context());
                let g = self.insights_ai.start();
                return self.stream(AiSlot::Insights, g, messages, 0.6);
            }

            // ---------- Assistant ----------
            Message::AssistantInput(a) => self.assistant.input.perform(a),
            Message::AssistantPrompt(p) => {
                self.page = Page::Assistant;
                self.assistant.input = text_editor::Content::with_text(&p);
                return self.update(Message::AssistantSend);
            }
            Message::AssistantSend => {
                let question = self.assistant.input.text().trim().to_string();
                if question.is_empty() || self.assistant.ai.running {
                    return Task::none();
                }
                if !self.ai_ready() {
                    self.page = Page::Settings;
                    self.settings.tab = SettingsTab::Ai;
                    self.toast(self.t("ai.setup_first"));
                    return Task::none();
                }
                self.assistant.history.push(ChatMessage::user(question));
                self.assistant.input = text_editor::Content::new();
                let tracked = self.tracked.clone().unwrap_or_default();
                let messages = prompts::assistant(&self.context(), &tracked, &self.assistant.history);
                let g = self.assistant.ai.start();
                let mut tasks = vec![self.stream(AiSlot::Assistant, g, messages, 0.8)];
                if let Some(c) = self.focus_channel().map(|c| c.id.clone()) {
                    tasks.push(self.load_insights(&c, false));
                }
                return Task::batch(tasks);
            }
            Message::AssistantClear => {
                self.assistant.history.clear();
                self.assistant.ai = AiRun { generation: self.assistant.ai.generation + 1, ..Default::default() };
            }
            Message::AssistantToStudio(i) => {
                if let Some(m) = self.assistant.history.get(i).cloned() {
                    if self.channels().is_empty() {
                        return Task::none();
                    }
                    self.open_editor(None, None);
                    let (title, body) = prompts::split_title(&m.content);
                    self.studio.title = title;
                    self.studio.body = text_editor::Content::with_text(&body);
                    self.studio.dirty = true;
                }
            }

            // ---------- Viewer ----------
            Message::View(source) => {
                self.page = Page::Viewer;
                if self.viewer.source.as_ref() != Some(&source) {
                    self.viewer.loaded = None;
                    self.viewer.ai = AiRun { generation: self.viewer.ai.generation + 1, ..Default::default() };
                }
                self.viewer.source = Some(source.clone());
                self.viewer.loading = true;
                self.viewer.error = None;
                self.viewer.scheduled = false;
                let client = self.client.clone();
                let s = source.clone();
                return Task::perform(
                    async move {
                        match s {
                            Source::Mine(id) => client.feed(id).await.map(Loaded::Feed),
                            Source::Public(u) => client.lookup(u).await.map(Loaded::Detail),
                        }
                    },
                    move |r| Message::ViewerLoaded(source.clone(), r),
                );
            }
            Message::ViewerInput(s) => self.viewer.input = s,
            Message::ViewerOpen => match crate::api::username_from(&self.viewer.input) {
                Some(u) => {
                    self.viewer.input.clear();
                    return self.update(Message::View(Source::Public(u)));
                }
                None => self.viewer.error = Some(ApiError { status: 400, message: String::new(), code: Some("invalid_username".into()) }),
            },
            Message::ViewerLoaded(source, r) => {
                if self.viewer.source.as_ref() != Some(&source) {
                    return Task::none();
                }
                self.viewer.loading = false;
                match r {
                    Ok(l) => {
                        let photo = match &l {
                            Loaded::Detail(d) => self.tracked_photo(&d.channel),
                            Loaded::Feed(_) => None,
                        };
                        self.viewer.loaded = Some(l);
                        return self.want_photo(photo);
                    }
                    Err(e) => self.viewer.error = Some(e),
                }
            }
            Message::ViewerScheduled(b) => self.viewer.scheduled = b,
            Message::ViewerAi => {
                if !self.ai_ready() {
                    self.page = Page::Settings;
                    self.settings.tab = SettingsTab::Ai;
                    self.toast(self.t("ai.setup_first"));
                    return Task::none();
                }
                let (title, posts, stats) = match &self.viewer.loaded {
                    Some(Loaded::Detail(d)) => (d.channel.title.clone(), d.posts.clone(), Some(d.stats.clone())),
                    Some(Loaded::Feed(f)) => (self.channel_by_id(&f.channel_id).map(|c| c.name.clone()).unwrap_or_default(), f.posts.clone(), f.stats.clone()),
                    None => return Task::none(),
                };
                let messages = prompts::teardown(&self.context(), &title, &posts, stats.as_ref());
                let g = self.viewer.ai.start();
                return self.stream(AiSlot::Viewer, g, messages, 0.6);
            }

            // ---------- Watchlist ----------
            Message::WatchInput(s) => {
                self.watch.input = s;
                self.watch.error = None;
            }
            Message::WatchAdd => {
                let input = self.watch.input.trim().to_string();
                if input.is_empty() {
                    return Task::none();
                }
                let username = crate::api::username_from(&input).unwrap_or(input);
                self.watch.adding = true;
                return Task::perform(self.client.clone().track(username), Message::WatchAdded);
            }
            Message::Track(username) => {
                return Task::perform(self.client.clone().track(username), Message::WatchAdded);
            }
            Message::WatchAdded(r) => {
                self.watch.adding = false;
                match r {
                    Ok(d) => {
                        self.watch.input.clear();
                        self.toast(self.i18n.tv("toast.tracked", &[("name", &d.channel.title)]));
                        if let Some(Loaded::Detail(v)) = self.viewer.loaded.as_mut() {
                            if v.channel.username.eq_ignore_ascii_case(&d.channel.username) {
                                v.channel.tracked = true;
                            }
                        }
                        return Task::perform(self.client.clone().tracked(), Message::Tracked);
                    }
                    Err(e) => {
                        let msg = self.api_error(&e);
                        if self.page == Page::Watchlist { self.watch.error = Some(msg) } else { self.error = Some(msg) }
                    }
                }
            }
            Message::WatchSort(col) => {
                if self.watch.sort == col {
                    self.watch.desc = !self.watch.desc;
                } else {
                    self.watch.sort = col;
                    self.watch.desc = col != Sort::Title;
                }
            }
            Message::WatchAskRemove(u) => self.watch.removing = u,
            Message::WatchRemove(u) => {
                self.watch.removing = None;
                let name = u.clone();
                return Task::perform(self.client.clone().untrack(u), move |r| Message::WatchRemoved(name.clone(), r));
            }
            Message::WatchRemoved(u, r) => match r {
                Ok(()) => {
                    let title = self.tracked.as_ref().and_then(|l| l.iter().find(|s| s.username.eq_ignore_ascii_case(&u))).map(|s| s.title.clone()).unwrap_or(format!("@{u}"));
                    if let Some(l) = self.tracked.as_mut() {
                        l.retain(|s| !s.username.eq_ignore_ascii_case(&u));
                    }
                    if let Some(Loaded::Detail(v)) = self.viewer.loaded.as_mut() {
                        if v.channel.username.eq_ignore_ascii_case(&u) {
                            v.channel.tracked = false;
                        }
                    }
                    self.toast(self.i18n.tv("toast.untracked", &[("name", &title)]));
                }
                Err(e) => self.error = Some(self.api_error(&e)),
            },
            Message::WatchRefresh(u) => {
                self.watch.refreshing = Some(u.clone());
                return Task::perform(self.client.clone().refresh(u), Message::WatchRefreshed);
            }
            Message::WatchRefreshed(r) => {
                self.watch.refreshing = None;
                match r {
                    Ok(d) => {
                        self.toast(self.i18n.tv("toast.refreshed", &[("name", &d.channel.title)]));
                        return Task::perform(self.client.clone().tracked(), Message::Tracked);
                    }
                    Err(e) => self.error = Some(self.api_error(&e)),
                }
            }
            Message::WatchAi => {
                if !self.ai_ready() {
                    self.page = Page::Settings;
                    self.settings.tab = SettingsTab::Ai;
                    self.toast(self.t("ai.setup_first"));
                    return Task::none();
                }
                let tracked = self.tracked.clone().unwrap_or_default();
                if tracked.is_empty() {
                    return Task::none();
                }
                let messages = prompts::compare(&self.context(), &tracked);
                let g = self.watch.ai.start();
                return self.stream(AiSlot::Watchlist, g, messages, 0.6);
            }

            // ---------- Channels ----------
            Message::TogglePause(id) => {
                if let Some(c) = self.channel_by_id(&id) {
                    let s = ChannelSettings { paused: !c.paused, timezone: c.timezone.clone(), signature: c.signature.clone() };
                    return Task::perform(self.client.clone().channel_settings(id, s), Message::ChannelSaved);
                }
            }
            Message::ChannelSaved(r) => {
                self.settings.channel_saving = false;
                match r {
                    Ok(c) => {
                        self.toast(self.i18n.tv(if c.paused { "toast.paused" } else { "toast.saved_channel" }, &[("name", &c.name)]));
                        if let Some(w) = self.workspace.as_mut() {
                            if let Some(x) = w.channels.iter_mut().find(|x| x.id == c.id) {
                                *x = c.clone();
                            }
                        }
                        if self.focus_channel().is_some_and(|f| f.id == c.id) {
                            self.fill_channel_form(&c);
                        }
                    }
                    Err(e) => self.error = Some(self.api_error(&e)),
                }
            }
            Message::Configure(id) => {
                self.channel = Some(id);
                self.settings.tab = SettingsTab::Channel;
                return self.update(Message::Navigate(Page::Settings));
            }

            // ---------- Settings ----------
            Message::SettingsTab(t) => {
                self.settings.tab = t;
                if self.page != Page::Settings {
                    return self.update(Message::Navigate(Page::Settings));
                }
            }
            Message::Server(s) => self.settings.server = s,
            Message::Token(s) => self.settings.token = s,
            Message::SaveConnection => {
                self.login_server = self.settings.server.clone();
                self.login_token = self.settings.token.clone();
                self.config.token = self.settings.token.trim().to_string();
                return self.update(Message::LoginSubmit);
            }
            Message::Provider(id) => {
                let p = ai::preset(&id);
                self.settings.provider = id;
                if !p.base_url.is_empty() {
                    self.settings.base_url = p.base_url.to_string();
                }
                self.settings.model = p.model.to_string();
                self.settings.models.clear();
                self.settings.ai_test = None;
            }
            Message::BaseUrl(s) => self.settings.base_url = s,
            Message::ApiKey(s) => self.settings.api_key = s,
            Message::ShowKey(b) => self.settings.show_key = b,
            Message::Model(s) => self.settings.model = s,
            Message::LoadModels => {
                self.settings.loading_models = true;
                return Task::perform(ai::models(self.ai_form()), Message::Models);
            }
            Message::Models(r) => {
                self.settings.loading_models = false;
                match r {
                    Ok(m) => self.settings.models = m,
                    Err(e) => self.settings.ai_test = Some(Err(e)),
                }
            }
            Message::TestAi => {
                self.settings.testing = true;
                self.settings.ai_test = None;
                let messages = vec![ChatMessage::system("You are a connectivity check. Answer in at most five words."), ChatMessage::user("Say hello to Gramalyzer Studio.")];
                return Task::perform(ai::complete(self.ai_form(), messages, 0.2), Message::AiTested);
            }
            Message::AiTested(r) => {
                self.settings.testing = false;
                self.settings.ai_test = Some(r);
            }
            Message::SaveAi => {
                self.config.ai = self.ai_form();
                self.toast(self.t("toast.settings_saved"));
                return self.save_config();
            }
            Message::BrandVoice(a) => self.settings.brand_voice.perform(a),
            Message::ContentLang(s) => self.settings.content_lang = s,
            Message::SaveWriting => {
                self.config.brand_voice = self.settings.brand_voice.text().trim().to_string();
                self.config.content_lang = self.settings.content_lang.clone();
                self.toast(self.t("toast.settings_saved"));
                return self.save_config();
            }
            Message::ChPaused(b) => self.settings.paused = b,
            Message::ChTimezone(s) => self.settings.timezone = s,
            Message::ChSignature(s) => self.settings.signature = s.chars().take(200).collect(),
            Message::SaveChannel => {
                if let Some(id) = self.focus_channel().map(|c| c.id.clone()) {
                    let s = ChannelSettings { paused: self.settings.paused, timezone: self.settings.timezone.clone(), signature: self.settings.signature.clone() };
                    self.settings.channel_saving = true;
                    return Task::perform(self.client.clone().channel_settings(id, s), Message::ChannelSaved);
                }
            }
        }
        Task::none()
    }

    fn ai_form(&self) -> AiConfig {
        AiConfig { provider: self.settings.provider.clone(), base_url: self.settings.base_url.trim().to_string(), api_key: self.settings.api_key.trim().to_string(), model: self.settings.model.trim().to_string() }
    }

    fn fill_channel_form(&mut self, c: &Channel) {
        self.settings.paused = c.paused;
        self.settings.timezone = c.timezone.clone();
        self.settings.signature = c.signature.clone();
    }

    /// The posts the Studio's filters show, newest first.
    pub fn library(&self) -> Vec<&Post> {
        let q = self.studio.search.to_lowercase();
        let mut posts: Vec<&Post> = self
            .posts()
            .iter()
            .filter(|p| self.channel.as_ref().is_none_or(|c| &p.channel_id == c))
            .filter(|p| self.studio.filter.is_none_or(|f| p.status == f))
            .filter(|p| q.is_empty() || p.title.to_lowercase().contains(&q) || p.body.to_lowercase().contains(&q) || p.category.to_lowercase().contains(&q))
            .collect();
        posts.sort_by(|a, b| b.scheduled_at.cmp(&a.scheduled_at));
        posts
    }

    fn csv(&self) -> String {
        let t = |k: &str| self.t(k);
        let demo = self.demo();
        let cell = |v: String| format!("\"{}\"", if v.starts_with(['=', '+', '@', '-']) { format!("'{v}") } else { v }.replace('"', "\"\""));
        let mut rows = vec![[t("csv.title"), t("csv.channel"), t("csv.status"), t("csv.date"), t("csv.views"), t("csv.reactions"), t("csv.shares")].map(cell).join(",")];
        for p in self.library() {
            let channel = self.channel_by_id(&p.channel_id).map(|c| c.name.clone()).unwrap_or_default();
            let status = t(&format!("status.{}", status_key(p.status)));
            let views = if demo { p.views.to_string() } else { t("csv.unavailable") };
            let reactions = if demo || p.status == PostStatus::Published { p.reactions.to_string() } else { String::new() };
            let shares = if demo { p.shares.to_string() } else { t("csv.unavailable") };
            rows.push([p.title.clone(), channel, status, p.scheduled_at.clone(), views, reactions, shares].map(cell).join(","));
        }
        format!("\u{feff}{}", rows.join("\r\n"))
    }

    pub fn subscription(&self) -> Subscription<Message> {
        let mut subs = vec![
            iced::time::every(Duration::from_secs(15)).map(|_| Message::Tick),
            keyboard::listen().filter_map(|e| match e {
                keyboard::Event::KeyPressed { key, modifiers, .. } => Some(Message::Key(key, modifiers)),
                _ => None,
            }),
        ];
        if self.toast.is_some() {
            subs.push(iced::time::every(Duration::from_millis(500)).map(|_| Message::ToastTick));
        }
        Subscription::batch(subs)
    }
}

pub fn status_key(s: PostStatus) -> &'static str {
    match s {
        PostStatus::Published => "published",
        PostStatus::Scheduled => "scheduled",
        PostStatus::Draft => "draft",
    }
}
