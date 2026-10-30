//! What the app asks the model. Prompts are English (models follow it best) and name the language to write in.
//! Every request carries the channel's context: what it is about, real posts to imitate, what the bot's analysis
//! says works, and its voice.
use crate::ai::ChatMessage;
use crate::app::{Rewrite, local};
use crate::model::*;
use std::fmt::Write;

pub struct Context<'a> {
    pub channel: Option<&'a Channel>,
    pub insights: Option<&'a Insights>,
    /// Workspace posts of every channel (drafts, queue, published).
    pub posts: &'a [Post],
    /// The channel's own published posts as Telegram has them, newest first.
    pub feed: &'a [FeedPost],
    pub voice: &'a str,
    /// What the channel's pictures show, as a model that can see images described them; None when nobody looked.
    pub look: Option<&'a str>,
    pub language: String,
    /// No language was chosen for drafts: write in the language of the channel's posts, `language` without any.
    pub language_from_posts: bool,
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
A post's text may be at most 4096 characters. Be concrete and specific; avoid clichés and filler.";

const FIT: &str = "Everything you write must fit this channel: its subject and audience, as the name, description and posts above show. \
Do not drift into generic topics (marketing, growth, AI, productivity, motivation) unless the channel is about them. \
Write posts the way the channel's own posts are written: similar length, structure, emoji and hashtag habits, and voice. \
Reuse the channel's hashtags rather than inventing new ones.";

/// How many of the channel's posts go into a prompt as examples, how much of each, and of all of them together.
const EXAMPLES: usize = 10;
const EXAMPLE_CHARS: usize = 600;
const EXAMPLE_BUDGET: usize = 3600;

fn kind_name(kind: &str) -> &'static str {
    match kind {
        "TEXT" => "text",
        "PHOTO" => "photo",
        "ALBUM" => "album",
        "VIDEO" => "video",
        "ANIMATION" => "GIF",
        "VIDEO_NOTE" => "video message",
        "STICKER" => "sticker",
        "VOICE" => "voice message",
        "AUDIO" => "audio",
        "DOCUMENT" => "file",
        "POLL" => "poll",
        _ => "other",
    }
}

/// Posts that are mainly something to look at: a photo, an album, a video, a GIF or a sticker.
pub fn is_picture(p: &FeedPost) -> bool {
    matches!(p.kind.as_str(), "PHOTO" | "ALBUM" | "VIDEO" | "ANIMATION" | "VIDEO_NOTE" | "STICKER")
}

/// The channel's own posts: forwards are someone else's style, and an empty text post says nothing.
pub fn own_posts(feed: &[FeedPost]) -> Vec<&FeedPost> {
    feed.iter().filter(|p| p.forwarded_from.is_none() && (!p.text.trim().is_empty() || p.kind != "TEXT")).collect()
}

/// The share of the channel's posts that are pictures, 0 to 1.
pub fn picture_share(feed: &[FeedPost]) -> f64 {
    let own = own_posts(feed);
    if own.is_empty() { 0.0 } else { own.iter().filter(|p| is_picture(p)).count() as f64 / own.len() as f64 }
}

/// Picture channels: most posts are pictures with a short caption or none.
fn is_picture_channel(ctx: &Context) -> bool {
    own_posts(ctx.feed).len() >= 3 && picture_share(ctx.feed) >= 0.5
}

fn day_name(day: &str) -> String {
    let mut s = day.to_lowercase();
    if let Some(f) = s.get_mut(0..1) {
        f.make_ascii_uppercase();
    }
    s
}

/// Cuts `text` to about `max` characters at a word boundary.
fn clip(text: &str, max: usize) -> String {
    let text = text.trim();
    if text.chars().count() <= max {
        return text.to_string();
    }
    let cut: String = text.chars().take(max).collect();
    let end = cut.rfind(char::is_whitespace).filter(|&i| i > max / 2).unwrap_or(cut.len());
    format!("{}…", cut[..end].trim_end())
}

fn is_emoji(c: char) -> bool {
    matches!(c as u32, 0x1F000..=0x1FAFF | 0x2600..=0x27BF | 0x2B00..=0x2BFF)
}

fn hashtags(text: &str) -> impl Iterator<Item = String> + '_ {
    text.split_whitespace()
        .filter_map(|w| w.strip_prefix('#'))
        .map(|t| t.trim_end_matches(|c: char| !c.is_alphanumeric() && c != '_'))
        .filter(|t| t.chars().next().is_some_and(char::is_alphabetic))
        .map(|t| format!("#{}", t.to_lowercase()))
}

/// The channel's own posts that are worth imitating: the most engaging ones first, then the newest. Picture posts
/// count too, with whatever caption they have: in a channel of pictures they are the style to learn.
fn examples<'a>(feed: &'a [FeedPost]) -> Vec<&'a FeedPost> {
    let own = own_posts(feed);
    let mut best = own.clone();
    best.sort_by(|a, b| b.reactions.cmp(&a.reactions));
    let opening = |p: &FeedPost| p.text.trim().lines().next().unwrap_or("").trim().to_lowercase();
    let mut out: Vec<&FeedPost> = Vec::new();
    let mut budget = EXAMPLE_BUDGET;
    // Recurring formats (a weekly digest, say) would otherwise fill every slot with the same opening.
    let mut add = |p: &'a FeedPost, out: &mut Vec<&'a FeedPost>| {
        let (o, cost) = (opening(p), p.text.trim().chars().count().min(EXAMPLE_CHARS) + 30);
        if out.iter().any(|q| q.id == p.id || (!o.is_empty() && opening(q) == o)) || (out.len() >= 3 && cost > budget) {
            return;
        }
        out.push(p);
        budget = budget.saturating_sub(cost);
    };
    for p in best {
        if out.len() >= EXAMPLES / 2 {
            break;
        }
        add(p, &mut out);
    }
    for p in own {
        if out.len() >= EXAMPLES {
            break;
        }
        add(p, &mut out);
    }
    out
}

/// What the channel posts, in numbers: formats, and for pictures how long their captions are.
fn content_mix(feed: &[FeedPost]) -> Option<String> {
    let own = own_posts(feed);
    if own.len() < 3 {
        return None;
    }
    let mut counts: Vec<(&str, usize)> = Vec::new();
    for p in &own {
        let k = kind_name(&p.kind);
        match counts.iter_mut().find(|(n, _)| *n == k) {
            Some((_, c)) => *c += 1,
            None => counts.push((k, 1)),
        }
    }
    counts.sort_by(|a, b| b.1.cmp(&a.1));
    let pct = |n: usize, of: usize| (n as f64 * 100.0 / of as f64).round() as usize;
    let mix: Vec<String> = counts.iter().take(5).map(|(k, n)| format!("{k} {}%", pct(*n, own.len()))).collect();
    let mut line = format!("Formats of the last {} posts: {}.", own.len(), mix.join(", "));
    let pictures: Vec<&&FeedPost> = own.iter().filter(|p| is_picture(p)).collect();
    if !pictures.is_empty() {
        let mut captions: Vec<usize> = pictures.iter().map(|p| p.text.trim().chars().count()).filter(|&n| n > 0).collect();
        captions.sort_unstable();
        let _ = write!(line, " Of the picture posts, {}% have no caption", pct(pictures.len() - captions.len(), pictures.len()));
        match captions.get(captions.len() / 2) {
            Some(n) => { let _ = write!(line, "; captions are typically about {n} characters."); }
            None => line.push('.'),
        }
    }
    Some(line)
}

/// One post as an example: what it is, how it did, and its text.
fn example(s: &mut String, p: &FeedPost, n: usize) {
    let text = p.text.trim();
    let text = if text.is_empty() { "(no caption)".to_string() } else { clip(text, EXAMPLE_CHARS) };
    let _ = writeln!(s, "[{n}] {}, {} reactions\n{text}\n", kind_name(&p.kind), p.reactions);
}

/// Habits the posts share, in numbers: length, emoji, hashtags, links.
fn house_style(feed: &[FeedPost]) -> Option<String> {
    let texts: Vec<&str> = feed.iter().filter(|p| p.forwarded_from.is_none()).map(|p| p.text.trim()).filter(|t| !t.is_empty()).collect();
    if texts.len() < 5 {
        return None;
    }
    let share = |f: &dyn Fn(&str) -> bool| texts.iter().filter(|t| f(t)).count() * 100 / texts.len();
    let mut lengths: Vec<usize> = texts.iter().map(|t| t.chars().count()).collect();
    lengths.sort_unstable();
    let mut tags: Vec<(String, usize)> = Vec::new();
    for t in &texts {
        for tag in hashtags(t) {
            match tags.iter_mut().find(|(k, _)| *k == tag) {
                Some((_, n)) => *n += 1,
                None => tags.push((tag, 1)),
            }
        }
    }
    tags.sort_by(|a, b| b.1.cmp(&a.1));
    let mut s = format!(
        "House style of the last {} posts: typically about {} characters; paragraphs in {}%; emoji in {}%; links in {}%; hashtags in {}%",
        texts.len(),
        lengths[lengths.len() / 2],
        share(&|t| t.contains("\n\n")),
        share(&|t| t.chars().any(is_emoji)),
        share(&|t| t.contains("http://") || t.contains("https://") || t.contains("t.me/")),
        share(&|t| hashtags(t).next().is_some()),
    );
    if !tags.is_empty() {
        let _ = write!(s, " (most used: {})", tags.iter().take(8).map(|(k, _)| k.as_str()).collect::<Vec<_>>().join(" "));
    }
    Some(s)
}

/// A description of the channel, its real posts and what its numbers say, for the system prompt.
pub fn describe(ctx: &Context) -> String {
    let mut s = String::new();
    let examples = examples(ctx.feed);
    if let Some(c) = ctx.channel {
        let _ = writeln!(s, "Channel: {} {}", c.name, c.handle);
        if !c.description.trim().is_empty() {
            let _ = writeln!(s, "About: {}", c.description.trim());
        }
        let _ = writeln!(s, "Subscribers: {}", c.subscribers);
        let mut mine: Vec<&Post> = ctx.posts.iter().filter(|p| p.channel_id == c.id).collect();
        mine.sort_by(|a, b| b.scheduled_at.cmp(&a.scheduled_at));
        if !examples.is_empty() {
            let _ = writeln!(s, "\nPosts from this channel, to learn its subject, voice and formatting (the most engaging first, then the newest):");
            for (n, p) in examples.iter().enumerate() {
                example(&mut s, p, n + 1);
            }
        } else {
            // Nothing from Telegram yet: the posts written in the workspace are the next best thing.
            let published: Vec<&&Post> = mine.iter().filter(|p| p.status == PostStatus::Published).take(EXAMPLES).collect();
            if !published.is_empty() {
                let _ = writeln!(s, "\nPosts from this channel, to learn its subject, voice and formatting:");
                for (n, p) in published.iter().enumerate() {
                    let _ = writeln!(s, "[{}] {}\n{}\n", n + 1, p.title, clip(&p.body, EXAMPLE_CHARS));
                }
            }
        }
        if let Some(mix) = content_mix(ctx.feed) {
            let _ = writeln!(s, "{mix}");
        }
        if let Some(style) = house_style(ctx.feed) {
            let _ = writeln!(s, "{style}");
        }
        if let Some(look) = ctx.look.map(str::trim).filter(|l| !l.is_empty()) {
            let _ = writeln!(s, "\nWhat the channel's pictures show (from looking at recent ones):\n{look}\n");
        }
        let planned: Vec<&str> = mine.iter().filter(|p| p.status != PostStatus::Published).map(|p| p.title.trim()).filter(|t| !t.is_empty()).take(8).collect();
        if !planned.is_empty() {
            let _ = writeln!(s, "Already drafted or scheduled (do not repeat): {}", planned.join("; "));
        }
    }
    if let Some(i) = ctx.insights.filter(|i| i.status == "ready") {
        let _ = writeln!(s, "\nAnalytics (lift vs. the channel's posts around the same time, 1.0 = typical; 90% range in brackets; a range that includes 1.0 means \"can't tell yet\"; sponsored posts left out; timezone {}):", i.timezone);
        let slot = |t: &InsightSlot| match (t.from, t.span) {
            (Some(from), Some(span)) if span > 1 => format!("{:02}:00–{:02}:00", from, (from + span) % 24),
            _ => format!("{:02}:00", t.hour),
        };
        let times: Vec<String> = i.best_times.iter().take(3).map(|t| format!("{} {} ({:.1}×)", day_name(&t.day), slot(t), t.lift)).collect();
        if !times.is_empty() {
            let _ = writeln!(s, "- Best times: {}", times.join(", "));
        }
        let groups = |g: &[InsightGroup]| g.iter().take(5).map(|g| match (g.low, g.high) {
            (Some(low), Some(high)) => format!("{} {:.1}× ({:.1}–{:.1})", g.key, g.lift, low, high),
            _ => format!("{} {:.1}×", g.key, g.lift),
        }).collect::<Vec<_>>().join(", ");
        if !i.content.kinds.is_empty() {
            let _ = writeln!(s, "- Formats: {}", groups(&i.content.kinds));
        }
        if !i.content.lengths.is_empty() {
            let _ = writeln!(s, "- Length (SHORT ≤120 chars, MEDIUM 121–400, LONG 401–800, XLONG 800+): {}", groups(&i.content.lengths));
        }
        if !i.content.topics.is_empty() {
            let _ = writeln!(s, "- Topics (by hashtag; the subject matters, adding a hashtag doesn't): {}", groups(&i.content.topics));
        }
        if !i.content.keywords.is_empty() {
            let _ = writeln!(s, "- Keywords: {}", groups(&i.content.keywords));
        }
        let _ = writeln!(s, "- Posts per week: {:.1}; average reactions {:.1}, comments {:.1}", i.cadence.posts_per_week, i.avg_reactions, i.avg_comments);
        if examples.is_empty() {
            let top: Vec<String> = i.top_posts.iter().take(4).filter(|p| !p.preview.is_empty()).map(|p| format!("\"{}\" ({} reactions, {} comments)", p.preview, p.reactions, p.comments)).collect();
            if !top.is_empty() {
                let _ = writeln!(s, "- Strongest posts: {}", top.join("; "));
            }
        }
    }
    if !ctx.voice.trim().is_empty() {
        let _ = writeln!(s, "Brand voice to follow: {}", ctx.voice.trim());
    }
    s
}

const PICTURES: &str = "Most of this channel's posts are pictures with a short caption or none: the picture carries the post. \
Gramalyzer sends only the text, so write what goes with a picture: as short as the channel's captions, in their voice, \
about the kind of thing its pictures show. Never turn that into a long text post the channel would not publish.";

/// Marks the line where a draft for a picture channel says what picture to attach.
pub const PICTURE_MARK: &str = "🖼";

fn system(ctx: &Context, extra: &str) -> ChatMessage {
    let about = describe(ctx);
    let fit = match ctx.channel {
        Some(_) if is_picture_channel(ctx) => format!("{FIT}\n{PICTURES}"),
        Some(_) => FIT.to_string(),
        None => String::new(),
    };
    let has_posts = !ctx.feed.is_empty() || ctx.channel.is_some_and(|c| ctx.posts.iter().any(|p| p.channel_id == c.id && p.status == PostStatus::Published));
    let language = if ctx.language_from_posts && has_posts {
        "Write in the language the channel's posts are written in, unless asked otherwise.".to_string()
    } else {
        format!("Write in {} unless asked otherwise.", ctx.language)
    };
    ChatMessage::system(format!("{ROLE}\n\n{about}\n{fit}\n{language}\n{extra}"))
}

pub fn rewrite(ctx: &Context, kind: Rewrite, title: &str, body: &str, brief: &str, tone: &str, target: &str) -> Vec<ChatMessage> {
    let only = "Answer with the post text only: no preface, no quotes around it, no explanation.";
    let task = match kind {
        Rewrite::Draft if is_picture_channel(ctx) => format!("Write a new post for this channel from the brief below: the caption, as short as the channel's captions and in their voice, then on a last line starting with \"{PICTURE_MARK} \" what picture to attach, in a few words. No preface, no quotes, no explanation.\n\nBrief: {brief}"),
        Rewrite::Draft => format!("Write a new Telegram post for this channel from the brief below, on the channel's subject and in the style of its posts. Open with a strong first line (it is what shows in the chat list), keep paragraphs short, end with a natural call to action or question. {only}\n\nBrief: {brief}"),
        Rewrite::Improve => format!("Edit this post to make it clearer, tighter and more engaging, keeping its meaning, facts and language. {only}"),
        Rewrite::Shorten => format!("Cut this post to about half its length without losing the key point. {only}"),
        Rewrite::Expand => format!("Expand this post with useful specifics, an example or a short list, staying under 1500 characters. {only}"),
        Rewrite::Hook => format!("Rewrite only the opening (first one or two lines) so it makes people stop scrolling, then keep the rest of the post as it is. {only}"),
        Rewrite::Grammar => format!("Fix spelling, grammar and punctuation only. Do not change wording or style otherwise. {only}"),
        Rewrite::Hashtags => "Suggest 1 to 3 hashtags that label this post's subject, so readers can find similar posts in the channel. Prefer the ones it already uses; Telegram has no hashtag feed, so they don't bring new readers. Answer with the hashtags on one line, separated by spaces, nothing else.".to_string(),
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
    let brief = if brief.trim().is_empty() {
        "what this channel's audience follows it for. Mix useful posts, a story or behind-the-scenes and a question to the audience, all on the channel's own subject.".to_string()
    } else {
        brief.trim().to_string()
    };
    let ask = if is_picture_channel(ctx) {
        "Every post must be on the channel's subject and look like its own posts: a picture and its caption. \
         Write each caption in full, as short as the channel's. Return ONLY a JSON array, no prose, like:\n\
         [{\"title\": \"short internal title\", \"picture\": \"what the picture should show\", \"category\": \"one or two words\", \"body\": \"the caption\"}]"
    } else {
        "Every post must be on the channel's subject and read like its own posts. \
         Write each post in full, ready to publish. Return ONLY a JSON array, no prose, like:\n\
         [{\"title\": \"short internal title\", \"category\": \"one or two words\", \"body\": \"the full post text\"}]"
    };
    vec![
        system(ctx, "You plan content calendars. Base ideas on the channel's subject and on what the analytics show working; vary formats; never repeat a recent post."),
        ChatMessage::user(format!("Plan the next {count} posts for this channel. Focus: {brief}\n{ask}")),
    ]
}

pub fn review(ctx: &Context) -> Vec<ChatMessage> {
    vec![
        system(ctx, "You explain analytics to busy channel owners."),
        ChatMessage::user(
            "Review this channel's analytics. Give: 1) three findings in plain words, only ones whose range is clearly above or below 1.0, and say when the data can't tell yet, 2) what to post more and less of, 3) a posting rhythm for next week with days and times (as tests to compare, not rules), 4) three post ideas that use what works. Don't recommend hashtags for reach or generic best times; Telegram shows posts to subscribers in order, so what matters is what this channel's readers respond to. Keep it under 300 words. Use short numbered sections and plain text.".to_string(),
        ),
    ]
}

pub fn assistant(ctx: &Context, tracked: &[TrackedSummary], history: &[ChatMessage]) -> Vec<ChatMessage> {
    let mut watch = String::new();
    if !tracked.is_empty() {
        watch.push_str("Channels on the user's watchlist (competitors/inspiration):\n");
        for s in tracked.iter().take(10) {
            let _ = writeln!(watch, "- {} @{}: {} subscribers, avg views {}, reach (views ÷ subscribers) {}%, {} posts/day", s.title, s.username, opt(s.subscribers), opt_f(s.avg_views), opt_f(s.err), opt_f(s.posts_per_day));
        }
    }
    let scheduled = ctx
        .channel
        .map(|c| ctx.posts.iter().filter(|p| p.channel_id == c.id && p.status == PostStatus::Scheduled).filter_map(|p| local(&p.scheduled_at).map(|d| format!("- {} {}", d.format("%a %d %b %H:%M"), p.title))).collect::<Vec<_>>())
        .unwrap_or_default();
    let queue = if scheduled.is_empty() { "Queue: nothing scheduled.".to_string() } else { format!("Queue:\n{}", scheduled.join("\n")) };
    let pictures = if is_picture_channel(ctx) {
        format!(" This channel posts pictures: when you suggest a post, write its caption and say what picture goes with it on a separate line starting with \"{PICTURE_MARK} \".")
    } else {
        String::new()
    };
    let mut messages = vec![system(ctx, &format!("{watch}{queue}\nYou are a helpful assistant in a chat. When you write a post, put it in full, ready to paste.{pictures}"))];
    messages.extend(history.iter().rev().take(16).rev().cloned());
    messages
}

pub fn teardown(ctx: &Context, title: &str, posts: &[FeedPost], stats: Option<&TrackedStats>) -> Vec<ChatMessage> {
    let mut s = format!("Channel to analyse: {title}\n");
    if let Some(st) = stats {
        let _ = writeln!(s, "Subscribers {}, avg views {}, reach (views ÷ subscribers) {}%, posts/day {}", opt(st.subscribers), opt_f(st.avg_views), opt_f(st.err), opt_f(st.posts_per_day));
    }
    let _ = writeln!(s, "Recent posts (format, views, reactions):");
    for p in posts.iter().take(20) {
        let text: String = p.text.chars().take(220).collect::<String>().replace('\n', " ");
        let text = if text.trim().is_empty() { "(no caption)" } else { &text };
        let _ = writeln!(s, "- [{}, {} views, {} reactions] {text}", kind_name(&p.kind), p.views.map(|v| v.to_string()).unwrap_or("?".into()), p.reactions);
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
        let _ = writeln!(s, "- {} @{}: {} subscribers, 7-day change {}, avg views {}, reach (views ÷ subscribers) {}%, {} posts/day", t.title, t.username, opt(t.subscribers), opt(t.change7), opt_f(t.avg_views), opt_f(t.err), opt_f(t.posts_per_day));
    }
    vec![
        system(ctx, "You compare Telegram channels."),
        ChatMessage::user(format!("{s}\nCompare these channels with mine: who is growing and what might explain it (say it's a guess: these numbers can't show causes), what reach is realistic for my size (reach usually falls as channels grow), and 3 moves for me this month. Under 250 words, plain text.")),
    ]
}

fn opt(v: Option<i64>) -> String {
    v.map(|v| v.to_string()).unwrap_or_else(|| "?".into())
}
fn opt_f(v: Option<f64>) -> String {
    v.map(|v| format!("{v:.1}")).unwrap_or_else(|| "?".into())
}

/// A draft and the picture it asks for: the lines marked 🖼 come out of the text.
pub fn split_picture(answer: &str) -> (String, String) {
    let lines: Vec<&str> = answer.trim().lines().collect();
    let marked = |l: &&&str| l.trim().starts_with(PICTURE_MARK);
    let text = lines.iter().filter(|l| !marked(l)).copied().collect::<Vec<_>>().join("\n").trim().to_string();
    let picture: Vec<&str> = lines
        .iter()
        .filter(marked)
        .map(|l| l.trim()[PICTURE_MARK.len()..].trim_start_matches(|c: char| c.is_whitespace() || matches!(c, '\u{FE0F}' | ':' | '：' | '-')).trim())
        .filter(|p| !p.is_empty())
        .collect();
    (text, picture.join("; "))
}

/// A title and body from an answer: the first short line becomes the title, or the picture it asks for.
pub fn split_title(answer: &str) -> (String, String) {
    let (body, picture) = split_picture(answer);
    let first: String = body.lines().map(str::trim).find(|l| !l.is_empty()).unwrap_or("").chars().filter(|c| !matches!(c, '*' | '#' | '"')).collect();
    let title = if picture.is_empty() { first.trim().to_string() } else { format!("{PICTURE_MARK} {picture}") };
    (title.chars().take(70).collect(), body)
}

/// The pictures a model looks at to describe a channel's: its two most engaging, then the newest, never forwards.
pub fn pictures_of(feed: &[FeedPost]) -> Vec<&FeedPost> {
    const SEEN: usize = 4;
    let own: Vec<&FeedPost> = feed.iter().filter(|p| p.forwarded_from.is_none() && is_picture(p) && p.media_url.as_deref().is_some_and(|u| !u.is_empty())).collect();
    let mut best = own.clone();
    best.sort_by(|a, b| b.reactions.cmp(&a.reactions));
    let mut out: Vec<&FeedPost> = best.into_iter().take(SEEN / 2).collect();
    for p in own {
        if out.len() >= SEEN {
            break;
        }
        if !out.iter().any(|q| q.id == p.id) {
            out.push(p);
        }
    }
    out
}

/// What the model for pictures is asked about them.
pub fn look_prompt(channel: &Channel, posts: &[&FeedPost]) -> String {
    let about = if channel.description.trim().is_empty() { String::new() } else { format!(" It describes itself as: {}", channel.description.trim()) };
    let captions: Vec<String> = posts
        .iter()
        .enumerate()
        .map(|(i, p)| {
            let t = p.text.trim();
            if t.is_empty() { format!("{}) (no caption)", i + 1) } else { format!("{}) \"{}\"", i + 1, t.chars().take(200).collect::<String>()) }
        })
        .collect();
    format!(
        "These are {} recent pictures from the Telegram channel \"{}\" ({}).{about}\nTheir captions, in order:\n{}\n\n\
         In 4 to 6 short lines starting with \"- \", describe what this channel's pictures are like, so a copywriter can write posts that fit: \
         their subjects and recurring themes, the humour or mood, the visual style (photos, memes, screenshots, illustrations…), \
         any text on them and its language, and what makes them recognisable. Describe what they have in common, not each picture. Plain text, no preface.",
        posts.len(),
        channel.name,
        channel.handle,
        captions.join("\n")
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn channel() -> Channel {
        Channel {
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
        }
    }

    fn feed_post(id: &str, text: &str, reactions: i64) -> FeedPost {
        FeedPost {
            id: id.into(),
            posted_at: String::new(),
            kind: "TEXT".into(),
            text: text.into(),
            views: None,
            reactions,
            reaction_list: vec![],
            comments: None,
            media_url: None,
            url: None,
            forwarded_from: None,
            edited: false,
        }
    }

    #[test]
    fn context_mentions_channel_and_voice() {
        let c = channel();
        let ctx = Context { channel: Some(&c), insights: None, posts: &[], feed: &[], voice: "warm, no jargon", look: None, language: "Ukrainian".into(), language_from_posts: false };
        let m = rewrite(&ctx, Rewrite::Shorten, "T", "Body", "", "friendly", "English");
        assert!(m[0].content.contains("Design Notes"));
        assert!(m[0].content.contains("warm, no jargon"));
        assert!(m[0].content.contains("Write in Ukrainian"));
        assert!(m[0].content.contains("subject and audience"));
        assert!(m[1].content.contains("Body"));
    }

    #[test]
    fn real_posts_ground_the_prompt() {
        let c = channel();
        let long = format!("Kerning matters 🎨\n\n{} #typography", "Letter pairs need care. ".repeat(40));
        let mut feed = vec![
            feed_post("1", "Five typefaces we keep coming back to\n\nAll free for commercial use. #typography", 40),
            feed_post("2", &long, 300),
            feed_post("3", "Rebrand teardown: what the new logo gets right #branding", 90),
            feed_post("4", "Moodboard of the week: soft gradients and paper textures #inspiration", 5),
            feed_post("5", "Empty states worth stealing for your next product #ux", 12),
            feed_post("6", "Posters from the Swiss archive that still feel modern #inspiration", 7),
            feed_post("7", "Colour palettes pulled from old film stills #inspiration", 3),
        ];
        feed.push(FeedPost { forwarded_from: Some("Other".into()), ..feed_post("8", "A forwarded post about crypto airdrops", 999) });
        feed.push(feed_post("9", "Kerning matters 🎨\n\nThe same series again. #typography", 250));
        let ctx = Context { channel: Some(&c), insights: None, posts: &[], feed: &feed, voice: "", look: None, language: "English".into(), language_from_posts: true };
        let sys = &plan(&ctx, 3, "")[0].content;
        // the most engaging posts come first, then the newest; forwards are someone else's words
        let p2 = sys.find("[1] text, 300 reactions").expect("best post first");
        assert!(sys[p2..].contains("[2] text, 90 reactions") && sys.contains("[3] text, 40 reactions") && sys.contains("[6] text, 5 reactions\nMoodboard"));
        assert!(!sys.contains("crypto") && !sys.contains("same series"));
        assert!(!sys.contains("[8]"));
        assert!(sys.contains("Five typefaces") && sys.contains("All free for commercial use"));
        assert!(sys.contains("Letter pairs need care") && !sys.contains(&long), "long posts are clipped");
        assert!(sys.contains("most used: #typography #inspiration #branding #ux"));
        assert!(sys.contains("language the channel's posts are written in"));
        assert!(!sys.contains("Write in English"));
        assert!(!plan(&ctx, 3, "")[1].content.contains("timely"));

        // with no posts at all there is nothing to copy the language from
        let ctx = Context { feed: &[], ..ctx };
        assert!(plan(&ctx, 3, "")[0].content.contains("Write in English"));
    }

    fn picture(id: &str, text: &str, reactions: i64, kind: &str) -> FeedPost {
        FeedPost { kind: kind.into(), media_url: Some(format!("https://cdn.example/{id}.jpg")), ..feed_post(id, text, reactions) }
    }

    fn memes() -> Vec<FeedPost> {
        vec![
            picture("m1", "", 900, "PHOTO"),
            picture("m2", "когда пятница", 700, "PHOTO"),
            picture("m3", "", 300, "PHOTO"),
            picture("m4", "мем дня", 200, "ALBUM"),
            feed_post("t1", "Канал о мемах про котов, без политики", 50),
            picture("m5", "", 100, "VIDEO"),
        ]
    }

    #[test]
    fn picture_posts_reach_the_prompt() {
        let c = channel();
        let feed = memes();
        let ctx = Context { channel: Some(&c), insights: None, posts: &[], feed: &feed, voice: "", look: None, language: "English".into(), language_from_posts: true };
        let text = describe(&ctx);
        assert!(text.contains("[1] photo, 900 reactions\n(no caption)"), "{text}");
        assert!(text.contains("photo, 700 reactions\nкогда пятница"));
        assert!(text.contains("Formats of the last 6 posts: photo 50%, album 17%, text 17%, video 17%."));
        assert!(text.contains("Of the picture posts, 60% have no caption; captions are typically about 13 characters."));
    }

    #[test]
    fn picture_channels_get_captions_and_the_picture_to_attach() {
        let c = channel();
        let feed = memes();
        let ctx = Context { channel: Some(&c), insights: None, posts: &[], feed: &feed, voice: "", look: Some("- cats in boxes, reaction memes\n- Russian text on the images"), language: "English".into(), language_from_posts: true };
        let m = rewrite(&ctx, Rewrite::Draft, "", "", "monday", "friendly", "English");
        assert!(m[0].content.contains("Most of this channel's posts are pictures"));
        assert!(m[0].content.contains("What the channel's pictures show (from looking at recent ones):\n- cats in boxes"));
        assert!(m[1].content.contains("last line starting with \"🖼 \""));
        assert!(plan(&ctx, 3, "")[1].content.contains("\"picture\": \"what the picture should show\""));
        assert!(assistant(&ctx, &[], &[])[0].content.contains("say what picture goes with it"));
        // a text channel gets none of that
        let words = [feed_post("1", "A long article about pasta and sauces", 5), feed_post("2", "Another long read about risotto", 4), feed_post("3", "Gnocchi by hand, step by step", 3)];
        let ctx = Context { feed: &words, look: None, ..ctx };
        assert!(!rewrite(&ctx, Rewrite::Draft, "", "", "x", "friendly", "English")[0].content.contains("Most of this channel's posts are pictures"));
    }

    #[test]
    fn picture_ideas_come_out_of_drafts() {
        assert_eq!(split_picture("Пятница, котики!\n🖼 кот в коробке с пивом"), ("Пятница, котики!".to_string(), "кот в коробке с пивом".to_string()));
        assert_eq!(split_picture("Котики\n🖼\u{FE0F} кот"), ("Котики".to_string(), "кот".to_string()));
        assert_eq!(split_title("Пятница, котики!\n🖼: кот в коробке"), ("🖼 кот в коробке".to_string(), "Пятница, котики!".to_string()));
        assert_eq!(split_picture("Just text"), ("Just text".to_string(), String::new()));
    }

    #[test]
    fn the_pictures_the_model_looks_at() {
        let mut feed = memes();
        feed.push(FeedPost { forwarded_from: Some("Other".into()), ..picture("f1", "", 5000, "PHOTO") });
        let ids: Vec<&str> = pictures_of(&feed).iter().map(|p| p.id.as_str()).collect();
        assert_eq!(ids, ["m1", "m2", "m3", "m4"]);
        let prompt = look_prompt(&channel(), &pictures_of(&feed));
        assert!(prompt.contains("4 recent pictures from the Telegram channel \"Design Notes\" (@designnotes). It describes itself as: UI tips"));
        assert!(prompt.contains("1) (no caption)\n2) \"когда пятница\""));
    }

    #[test]
    fn clip_cuts_at_a_word() {
        assert_eq!(clip("short", 10), "short");
        assert_eq!(clip("one two three four", 12), "one two…");
        assert_eq!(hashtags("Hi #Design, #ux! #1 a#b").collect::<Vec<_>>(), vec!["#design", "#ux"]);
    }

    #[test]
    fn titles_from_answers() {
        assert_eq!(split_title("**Big news**\n\nWe launched.").0, "Big news");
    }
}
