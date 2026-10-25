//! The backend's JSON, mirroring GramalyzerVibe's shared/types.ts. Fields the app does not read are left out;
//! serde ignores them, so a newer backend keeps working.
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Channel {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub handle: String,
    #[serde(default)]
    pub initials: String,
    #[serde(default)]
    pub color: String,
    #[serde(default)]
    pub subscribers: i64,
    #[serde(default)]
    pub growth: f64,
    #[serde(default)]
    pub views: i64,
    #[serde(default)]
    pub engagement: f64,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub paused: bool,
    #[serde(default = "utc")]
    pub timezone: String,
    #[serde(default)]
    pub notifications: bool,
    #[serde(default)]
    pub auto_delete: bool,
    #[serde(default)]
    pub signature: String,
    #[serde(default)]
    pub telegram_chat_id: Option<i64>,
}
fn utc() -> String {
    "UTC".into()
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "lowercase")]
pub enum PostStatus {
    Published,
    Scheduled,
    Draft,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Post {
    pub id: String,
    pub channel_id: String,
    pub title: String,
    pub body: String,
    pub status: PostStatus,
    pub scheduled_at: String,
    #[serde(default)]
    pub views: i64,
    #[serde(default)]
    pub reactions: i64,
    #[serde(default)]
    pub shares: i64,
    #[serde(default)]
    pub category: String,
    #[serde(default)]
    pub delivery_state: Option<String>,
    #[serde(default)]
    pub delivery_error: Option<String>,
}

impl Post {
    /// Published posts and ones the bot is sending (or may have sent) can't be edited any more.
    pub fn read_only(&self) -> bool {
        self.status == PostStatus::Published || matches!(self.delivery_state.as_deref(), Some("sending" | "uncertain"))
    }
}

/// The body of POST /api/posts and PATCH /api/posts/{id}.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PostInput {
    pub channel_id: String,
    pub title: String,
    pub body: String,
    pub scheduled_at: String,
    pub status: PostStatus,
    pub category: String,
}

#[derive(Debug, Clone, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HistoryPoint {
    pub channel_id: String,
    pub date: String,
    pub subscribers: i64,
}

#[derive(Debug, Clone, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Workspace {
    pub channels: Vec<Channel>,
    pub posts: Vec<Post>,
    #[serde(default)]
    pub demo: bool,
    #[serde(default)]
    pub bot_enabled: bool,
    #[serde(default)]
    pub history: Vec<HistoryPoint>,
}

/// The channel settings the website's Settings page saves (PATCH /api/channels/{id}).
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ChannelSettings {
    pub paused: bool,
    pub timezone: String,
    pub signature: String,
}

#[derive(Debug, Clone, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct InsightSlot {
    pub day: String,
    pub hour: u32,
    pub lift: f64,
    #[serde(default)]
    pub support: i64,
}

#[derive(Debug, Clone, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct InsightGroup {
    pub key: String,
    pub posts: i64,
    pub lift: f64,
}

#[derive(Debug, Clone, Deserialize, PartialEq)]
pub struct Suggestion {
    pub at: String,
    pub day: String,
    pub hour: u32,
    pub lift: f64,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Default)]
pub struct Counts {
    pub total: i64,
    pub eligible: i64,
    pub excluded: i64,
    pub immature: i64,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Default)]
pub struct Heatmap {
    pub lift: Vec<Vec<f64>>,
    pub posts: Vec<Vec<i64>>,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Default)]
pub struct Content {
    #[serde(default)]
    pub kinds: Vec<InsightGroup>,
    #[serde(default)]
    pub lengths: Vec<InsightGroup>,
    #[serde(default)]
    pub links: Vec<InsightGroup>,
    #[serde(default)]
    pub topics: Vec<InsightGroup>,
    #[serde(default)]
    pub keywords: Vec<InsightGroup>,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct Cadence {
    pub posts_per_week: f64,
    pub longest_gap_days: f64,
}

#[derive(Debug, Clone, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct TopPost {
    pub message_id: i64,
    pub posted_at: String,
    pub kind: String,
    #[serde(default)]
    pub preview: String,
    pub reactions: i64,
    pub comments: i64,
    pub url: Option<String>,
}

#[derive(Debug, Clone, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Insights {
    pub channel_id: String,
    /// ready, learning or not_connected.
    pub status: String,
    #[serde(default)]
    pub timezone: String,
    #[serde(default)]
    pub metric_formula: String,
    #[serde(default)]
    pub window_days: i64,
    #[serde(default)]
    pub confidence: String,
    #[serde(default)]
    pub counts: Counts,
    #[serde(default)]
    pub posts_needed: i64,
    #[serde(default)]
    pub avg_reactions: f64,
    #[serde(default)]
    pub avg_comments: f64,
    #[serde(default)]
    pub best_times: Vec<InsightSlot>,
    #[serde(default)]
    pub experiments: Vec<InsightSlot>,
    #[serde(default)]
    pub suggestions: Vec<Suggestion>,
    #[serde(default)]
    pub heatmap: Heatmap,
    #[serde(default)]
    pub content: Content,
    #[serde(default)]
    pub cadence: Cadence,
    #[serde(default)]
    pub top_posts: Vec<TopPost>,
}

#[derive(Debug, Clone, Deserialize, PartialEq)]
pub struct Reaction {
    pub emoji: String,
    pub count: i64,
}

#[derive(Debug, Clone, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct FeedPost {
    pub id: String,
    pub posted_at: String,
    pub kind: String,
    #[serde(default)]
    pub text: String,
    pub views: Option<i64>,
    #[serde(default)]
    pub reactions: i64,
    #[serde(default)]
    pub reaction_list: Vec<Reaction>,
    pub comments: Option<i64>,
    /// The post's picture (a photo, a video's thumbnail); only posts read from the public t.me page have one.
    #[serde(default)]
    pub media_url: Option<String>,
    pub url: Option<String>,
    pub forwarded_from: Option<String>,
    #[serde(default)]
    pub edited: bool,
}

#[derive(Debug, Clone, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SeriesPoint {
    pub day: String,
    pub subscribers: Option<i64>,
    pub avg_views: Option<f64>,
}

#[derive(Debug, Clone, Deserialize, PartialEq)]
pub struct KindStat {
    pub kind: String,
    pub posts: i64,
    #[serde(rename = "avgViews")]
    pub avg_views: Option<f64>,
}

#[derive(Debug, Clone, Deserialize, PartialEq)]
pub struct TagStat {
    pub tag: String,
    pub posts: i64,
    #[serde(rename = "avgViews")]
    pub avg_views: Option<f64>,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct TrackedStats {
    pub subscribers: Option<i64>,
    pub change7: Option<i64>,
    pub change30: Option<i64>,
    #[serde(default)]
    pub series: Vec<SeriesPoint>,
    pub avg_views: Option<f64>,
    pub er: Option<f64>,
    pub avg_reactions: Option<f64>,
    pub reaction_rate: Option<f64>,
    pub posts_per_day: Option<f64>,
    pub last_post: Option<String>,
    #[serde(default)]
    pub kinds: Vec<KindStat>,
    #[serde(default)]
    pub hashtags: Vec<TagStat>,
    #[serde(default)]
    pub hours: Vec<i64>,
    #[serde(default)]
    pub days: Vec<i64>,
    #[serde(default)]
    pub sample: i64,
}

#[derive(Debug, Clone, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ChannelFeed {
    pub channel_id: String,
    pub source: String,
    #[serde(default)]
    pub posts: Vec<FeedPost>,
    #[serde(default)]
    pub scheduled: Vec<FeedPost>,
    pub stats: Option<TrackedStats>,
}

#[derive(Debug, Clone, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct TrackedSummary {
    pub username: String,
    pub title: String,
    pub description: Option<String>,
    pub photo_url: Option<String>,
    #[serde(default)]
    pub verified: bool,
    pub subscribers: Option<i64>,
    pub change7: Option<i64>,
    pub avg_views: Option<f64>,
    pub er: Option<f64>,
    pub posts_per_day: Option<f64>,
    pub last_post: Option<String>,
    pub fetched_at: Option<String>,
    pub error: Option<String>,
    #[serde(default)]
    pub demo: bool,
    #[serde(default)]
    pub tracked: bool,
    #[serde(default)]
    pub series: Vec<i64>,
    pub url: Option<String>,
}

#[derive(Debug, Clone, Deserialize, PartialEq)]
pub struct TrackedDetail {
    pub channel: TrackedSummary,
    pub stats: TrackedStats,
    #[serde(default)]
    pub top: Vec<FeedPost>,
    #[serde(default)]
    pub posts: Vec<FeedPost>,
}
