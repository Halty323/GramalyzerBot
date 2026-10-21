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

/// How many of the channel's posts go into a prompt as examples, and how much of each.
const EXAMPLES: usize = 6;
const EXAMPLE_CHARS: usize = 600;

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

/// The channel's own posts that are worth imitating: the most engaging ones first, then the newest.
fn examples<'a>(feed: &'a [FeedPost]) -> Vec<&'a FeedPost> {
    let own: Vec<&FeedPost> = feed.iter().filter(|p| p.forwarded_from.is_none() && p.text.trim().chars().count() >= 20).collect();
    let mut best = own.clone();
    best.sort_by(|a, b| b.reactions.cmp(&a.reactions));
    let opening = |p: &FeedPost| p.text.trim().lines().next().unwrap_or("").trim().to_lowercase();
    let mut out: Vec<&FeedPost> = Vec::new();
    // Recurring formats (a weekly digest, say) would otherwise fill every slot with the same opening.
    let add = |p: &'a FeedPost, out: &mut Vec<&'a FeedPost>| {
        if !out.iter().any(|q| q.id == p.id || opening(q) == opening(p)) {
            out.push(p);
        }
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
                let _ = writeln!(s, "[{}] {} reactions\n{}\n", n + 1, p.reactions, clip(&p.text, EXAMPLE_CHARS));
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
        if let Some(style) = house_style(ctx.feed) {
            let _ = writeln!(s, "{style}");
        }
        let planned: Vec<&str> = mine.iter().filter(|p| p.status != PostStatus::Published).map(|p| p.title.trim()).filter(|t| !t.is_empty()).take(8).collect();
        if !planned.is_empty() {
            let _ = writeln!(s, "Already drafted or scheduled (do not repeat): {}", planned.join("; "));
        }
    }
    if let Some(i) = ctx.insights.filter(|i| i.status == "ready") {
        let _ = writeln!(s, "\nAnalytics (engagement lift vs. the channel's typical post, 1.0 = typical; timezone {}):", i.timezone);
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

fn system(ctx: &Context, extra: &str) -> ChatMessage {
    let about = describe(ctx);
    let fit = if ctx.channel.is_some() { FIT } else { "" };
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
        Rewrite::Draft => format!("Write a new Telegram post for this channel from the brief below, on the channel's subject and in the style of its posts. Open with a strong first line (it is what shows in the chat list), keep paragraphs short, end with a natural call to action or question. {only}\n\nBrief: {brief}"),
        Rewrite::Improve => format!("Edit this post to make it clearer, tighter and more engaging, keeping its meaning, facts and language. {only}"),
        Rewrite::Shorten => format!("Cut this post to about half its length without losing the key point. {only}"),
        Rewrite::Expand => format!("Expand this post with useful specifics, an example or a short list, staying under 1500 characters. {only}"),
        Rewrite::Hook => format!("Rewrite only the opening (first one or two lines) so it makes people stop scrolling, then keep the rest of the post as it is. {only}"),
        Rewrite::Grammar => format!("Fix spelling, grammar and punctuation only. Do not change wording or style otherwise. {only}"),
        Rewrite::Hashtags => "Suggest 3 to 6 hashtags for this post that fit the channel (prefer the ones it already uses and the analytics show working). Answer with the hashtags on one line, separated by spaces, nothing else.".to_string(),
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
    vec![
        system(ctx, "You plan content calendars. Base ideas on the channel's subject and on what the analytics show working; vary formats; never repeat a recent post."),
        ChatMessage::user(format!(
            "Plan the next {count} posts for this channel. Focus: {brief}\n\
             Every post must be on the channel's subject and read like its own posts. \
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
            url: None,
            forwarded_from: None,
            edited: false,
        }
    }

    #[test]
    fn context_mentions_channel_and_voice() {
        let c = channel();
        let ctx = Context { channel: Some(&c), insights: None, posts: &[], feed: &[], voice: "warm, no jargon", language: "Ukrainian".into(), language_from_posts: false };
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
        let ctx = Context { channel: Some(&c), insights: None, posts: &[], feed: &feed, voice: "", language: "English".into(), language_from_posts: true };
        let sys = &plan(&ctx, 3, "")[0].content;
        // the most engaging posts come first, then the newest; forwards are someone else's words
        let p2 = sys.find("[1] 300 reactions").expect("best post first");
        assert!(sys[p2..].contains("[2] 90 reactions") && sys.contains("[3] 40 reactions") && sys.contains("[4] 5 reactions\nMoodboard"));
        assert!(!sys.contains("crypto") && !sys.contains("same series"));
        assert!(!sys.contains("[7]"));
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
