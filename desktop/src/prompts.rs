//! What the app asks the model. Prompts are English (models follow it best) and name the language to write in.
//! Every request carries the channel's context: what it is, what the bot's analysis says works, and its voice.
use crate::ai::ChatMessage;
use crate::app::{Rewrite, local};
use crate::model::*;
use std::fmt::Write;

pub struct Context<'a> {
    pub channel: Option<&'a Channel>,
    pub insights: Option<&'a Insights>,
    pub posts: &'a [Post],
    pub voice: &'a str,
    pub language: String,
}

pub fn language_name(code: &str) -> &'static str {
    match code {
        "ru" => "Russian",
        "uk" => "Ukrainian",
        "hi" => "Hindi",
        "id" => "Indonesian",
        "pt" => "Portuguese (Brazil)",
        "es" => "Spanish",
        "ar" => "Arabic",
        "fa" => "Persian",
        "uz" => "Uzbek (Latin script)",
        "tr" => "Turkish",
        "vi" => "Vietnamese",
        _ => "English",
    }
}

const ROLE: &str = "You are the content strategist and copywriter inside Gramalyzer Studio, a tool for running Telegram channels. \
Telegram posts are sent as plain text: never use Markdown (no **bold**, no # headings, no [links](…)); emoji and line breaks are fine. \
A post's text may be at most 4096 characters; good channel posts are usually 300–1200. Be concrete and specific; avoid clichés and filler.";

fn day_name(day: &str) -> String {
    let mut s = day.to_lowercase();
    if let Some(f) = s.get_mut(0..1) {
        f.make_ascii_uppercase();
    }
    s
}

/// A compact description of the channel and what its numbers say, for the system prompt.
pub fn describe(ctx: &Context) -> String {
    let mut s = String::new();
    if let Some(c) = ctx.channel {
        let _ = writeln!(s, "Channel: {} {}", c.name, c.handle);
        if !c.description.trim().is_empty() {
            let _ = writeln!(s, "About: {}", c.description.trim());
        }
        let _ = writeln!(s, "Subscribers: {}", c.subscribers);
        let recent: Vec<&Post> = {
            let mut v: Vec<&Post> = ctx.posts.iter().filter(|p| p.channel_id == c.id && p.status == PostStatus::Published).collect();
            v.sort_by(|a, b| b.scheduled_at.cmp(&a.scheduled_at));
            v.into_iter().take(6).collect()
        };
        if !recent.is_empty() {
            let _ = writeln!(s, "Recent posts (title — first line):");
            for p in recent {
                let first: String = p.body.lines().next().unwrap_or("").chars().take(140).collect();
                let _ = writeln!(s, "- {} — {}", p.title, first);
            }
        }
    }
    if let Some(i) = ctx.insights.filter(|i| i.status == "ready") {
        let _ = writeln!(s, "Analytics (engagement lift vs. the channel's typical post, 1.0 = typical; timezone {}):", i.timezone);
        let times: Vec<String> = i.best_times.iter().take(3).map(|t| format!("{} {:02}:00 ({:.1}×)", day_name(&t.day), t.hour, t.lift)).collect();
        if !times.is_empty() {
            let _ = writeln!(s, "- Best times: {}", times.join(", "));
        }
        let groups = |g: &[InsightGroup]| g.iter().take(5).map(|g| format!("{} {:.1}×", g.key, g.lift)).collect::<Vec<_>>().join(", ");
        if !i.content.kinds.is_empty() {
            let _ = writeln!(s, "- Formats: {}", groups(&i.content.kinds));
        }
        if !i.content.lengths.is_empty() {
            let _ = writeln!(s, "- Length (SHORT ≤120 chars, MEDIUM 121–400, LONG 401–800, XLONG 800+): {}", groups(&i.content.lengths));
        }
        if !i.content.topics.is_empty() {
            let _ = writeln!(s, "- Hashtags: {}", groups(&i.content.topics));
        }
        if !i.content.keywords.is_empty() {
            let _ = writeln!(s, "- Keywords: {}", groups(&i.content.keywords));
        }
        let _ = writeln!(s, "- Posts per week: {:.1}; average reactions {:.1}, comments {:.1}", i.cadence.posts_per_week, i.avg_reactions, i.avg_comments);
        let top: Vec<String> = i.top_posts.iter().take(4).filter(|p| !p.preview.is_empty()).map(|p| format!("\"{}\" ({} reactions, {} comments)", p.preview.chars().take(120).collect::<String>(), p.reactions, p.comments)).collect();
        if !top.is_empty() {
            let _ = writeln!(s, "- Strongest posts: {}", top.join("; "));
        }
    }
    if !ctx.voice.trim().is_empty() {
        let _ = writeln!(s, "Brand voice to follow: {}", ctx.voice.trim());
    }
    s
}

fn system(ctx: &Context, extra: &str) -> ChatMessage {
    let about = describe(ctx);
    ChatMessage::system(format!("{ROLE}\n\n{about}\nWrite in {} unless asked otherwise.\n{extra}", ctx.language))
}

pub fn rewrite(ctx: &Context, kind: Rewrite, title: &str, body: &str, brief: &str, tone: &str, target: &str) -> Vec<ChatMessage> {
    let only = "Answer with the post text only: no preface, no quotes around it, no explanation.";
    let task = match kind {
        Rewrite::Draft => format!("Write a new Telegram post for this channel from the brief below. Open with a strong first line (it is what shows in the chat list), keep paragraphs short, end with a natural call to action or question. {only}\n\nBrief: {brief}"),
        Rewrite::Improve => format!("Edit this post to make it clearer, tighter and more engaging, keeping its meaning, facts and language. {only}"),
        Rewrite::Shorten => format!("Cut this post to about half its length without losing the key point. {only}"),
        Rewrite::Expand => format!("Expand this post with useful specifics, an example or a short list, staying under 1500 characters. {only}"),
        Rewrite::Hook => format!("Rewrite only the opening (first one or two lines) so it makes people stop scrolling, then keep the rest of the post as it is. {only}"),
        Rewrite::Grammar => format!("Fix spelling, grammar and punctuation only. Do not change wording or style otherwise. {only}"),
        Rewrite::Hashtags => "Suggest 3 to 6 hashtags for this post that fit the channel (prefer ones the analytics show working). Answer with the hashtags on one line, separated by spaces, nothing else.".to_string(),
        Rewrite::Headlines => "Suggest 5 short internal titles (under 70 characters) for this post, one per line, numbered. Nothing else.".to_string(),
        Rewrite::Translate => format!("Translate this post into {target}, keeping its tone, emoji and line breaks; adapt idioms naturally. {only}"),
        Rewrite::Tone => format!("Rewrite this post in a {tone} tone, keeping its facts and language. {only}"),
    };
    let mut messages = vec![system(ctx, "")];
    if kind == Rewrite::Draft {
        messages.push(ChatMessage::user(task));
    } else {
        messages.push(ChatMessage::user(format!("{task}\n\nTitle: {title}\n\nPost:\n{body}")));
    }
    messages
}

pub fn plan(ctx: &Context, count: usize, brief: &str) -> Vec<ChatMessage> {
    let brief = if brief.trim().is_empty() { "Mix formats: practical value, a story or behind-the-scenes, a question to the audience, and something timely.".to_string() } else { brief.trim().to_string() };
    vec![
        system(ctx, "You plan content calendars. Base ideas on what the analytics show working; vary formats; never repeat a recent post."),
        ChatMessage::user(format!(
            "Plan the next {count} posts for this channel. Focus: {brief}\n\
             Write each post in full, ready to publish. Return ONLY a JSON array, no prose, like:\n\
             [{{\"title\": \"short internal title\", \"category\": \"one or two words\", \"body\": \"the full post text\"}}]"
        )),
    ]
}

pub fn review(ctx: &Context) -> Vec<ChatMessage> {
    vec![
        system(ctx, "You explain analytics to busy channel owners."),
        ChatMessage::user(
            "Review this channel's analytics. Give: 1) three findings in plain words, 2) what to post more and less of, 3) a posting rhythm for next week with days and times, 4) three post ideas that use what works. Keep it under 300 words. Use short numbered sections and plain text.".to_string(),
        ),
    ]
}

pub fn assistant(ctx: &Context, tracked: &[TrackedSummary], history: &[ChatMessage]) -> Vec<ChatMessage> {
    let mut watch = String::new();
    if !tracked.is_empty() {
        watch.push_str("Channels on the user's watchlist (competitors/inspiration):\n");
        for s in tracked.iter().take(10) {
            let _ = writeln!(watch, "- {} @{}: {} subscribers, avg views {}, ER {}%, {} posts/day", s.title, s.username, opt(s.subscribers), opt_f(s.avg_views), opt_f(s.er), opt_f(s.posts_per_day));
        }
    }
    let scheduled = ctx
        .channel
        .map(|c| ctx.posts.iter().filter(|p| p.channel_id == c.id && p.status == PostStatus::Scheduled).filter_map(|p| local(&p.scheduled_at).map(|d| format!("- {} {}", d.format("%a %d %b %H:%M"), p.title))).collect::<Vec<_>>())
        .unwrap_or_default();
    let queue = if scheduled.is_empty() { "Queue: nothing scheduled.".to_string() } else { format!("Queue:\n{}", scheduled.join("\n")) };
    let mut messages = vec![system(ctx, &format!("{watch}{queue}\nYou are a helpful assistant in a chat. When you write a post, put it in full, ready to paste."))];
    messages.extend(history.iter().rev().take(16).rev().cloned());
    messages
}

pub fn teardown(ctx: &Context, title: &str, posts: &[FeedPost], stats: Option<&TrackedStats>) -> Vec<ChatMessage> {
    let mut s = format!("Channel to analyse: {title}\n");
    if let Some(st) = stats {
        let _ = writeln!(s, "Subscribers {}, avg views {}, ER {}%, posts/day {}", opt(st.subscribers), opt_f(st.avg_views), opt_f(st.er), opt_f(st.posts_per_day));
    }
    let _ = writeln!(s, "Recent posts (views, reactions):");
    for p in posts.iter().take(20) {
        let text: String = p.text.chars().take(220).collect::<String>().replace('\n', " ");
        let _ = writeln!(s, "- [{} views, {} reactions] {}", p.views.map(|v| v.to_string()).unwrap_or("?".into()), p.reactions, text);
    }
    vec![
        system(ctx, "You analyse other Telegram channels so the user can learn from them."),
        ChatMessage::user(format!(
            "{s}\nBreak down this channel: what topics and formats get the most views and reactions, how they write openings, posting rhythm, and 3 concrete ideas my channel could adapt (not copy). Under 300 words, plain text with short numbered sections."
        )),
    ]
}

pub fn compare(ctx: &Context, tracked: &[TrackedSummary]) -> Vec<ChatMessage> {
    let mut s = String::from("Watchlist:\n");
    for t in tracked.iter().take(15) {
        let _ = writeln!(s, "- {} @{}: {} subscribers, 7-day change {}, avg views {}, ER {}%, {} posts/day", t.title, t.username, opt(t.subscribers), opt(t.change7), opt_f(t.avg_views), opt_f(t.er), opt_f(t.posts_per_day));
    }
    vec![
        system(ctx, "You compare Telegram channels."),
        ChatMessage::user(format!("{s}\nCompare these channels with mine: who is growing and why it might be, which engagement levels are realistic for my size, and 3 moves for me this month. Under 250 words, plain text.")),
    ]
}

fn opt(v: Option<i64>) -> String {
    v.map(|v| v.to_string()).unwrap_or_else(|| "?".into())
}
fn opt_f(v: Option<f64>) -> String {
    v.map(|v| format!("{v:.1}")).unwrap_or_else(|| "?".into())
}

/// A title and body from an assistant answer: the first short line becomes the title.
pub fn split_title(answer: &str) -> (String, String) {
    let body = answer.trim().to_string();
    let first: String = body.lines().map(str::trim).find(|l| !l.is_empty()).unwrap_or("").chars().filter(|c| !matches!(c, '*' | '#' | '"')).collect();
    let title: String = first.trim().chars().take(70).collect();
    (title, body)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn context_mentions_channel_and_voice() {
        let c = Channel {
            id: "a".into(),
            name: "Design Notes".into(),
            handle: "@designnotes".into(),
            initials: "DN".into(),
            color: String::new(),
            subscribers: 1200,
            growth: 0.0,
            views: 0,
            engagement: 0.0,
            description: "UI tips".into(),
            paused: false,
            timezone: "UTC".into(),
            notifications: false,
            auto_delete: false,
            signature: String::new(),
            telegram_chat_id: None,
        };
        let ctx = Context { channel: Some(&c), insights: None, posts: &[], voice: "warm, no jargon", language: "Ukrainian".into() };
        let m = rewrite(&ctx, Rewrite::Shorten, "T", "Body", "", "friendly", "English");
        assert!(m[0].content.contains("Design Notes"));
        assert!(m[0].content.contains("warm, no jargon"));
        assert!(m[0].content.contains("Write in Ukrainian"));
        assert!(m[1].content.contains("Body"));
    }

    #[test]
    fn titles_from_answers() {
        assert_eq!(split_title("**Big news**\n\nWe launched.").0, "Big news");
    }
}
