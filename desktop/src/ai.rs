//! The AI layer: any OpenAI-compatible chat-completions endpoint. Providers differ only in base URL, key and model,
//! so a key from any of the free tiers below (or a local Ollama) works the same way.
use futures::{Stream, StreamExt, stream};
use serde::{Deserialize, Serialize};
use std::time::Duration;

/// A provider the settings page offers, with the free tier it had when this list was checked (2026-10).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Preset {
    pub id: &'static str,
    pub name: &'static str,
    pub base_url: &'static str,
    pub model: &'static str,
    pub key_url: &'static str,
    /// Shown under the picker; numbers are the provider's published free limits and change often.
    pub free_tier: &'static str,
    pub needs_key: bool,
    /// A free model of the provider that can see images, to describe a channel's pictures; empty when there is none.
    pub vision: &'static str,
}

pub const PRESETS: &[Preset] = &[
    Preset {
        id: "gemini",
        name: "Google AI Studio (Gemini)",
        base_url: "https://generativelanguage.googleapis.com/v1beta/openai",
        model: "gemini-flash-latest",
        key_url: "https://aistudio.google.com/apikey",
        free_tier: "Free key with a Google account, no card. Per-model daily quotas (see AI Studio). Outside the EU/UK free prompts may be used to improve Google's models.",
        needs_key: true,
        vision: "gemini-flash-latest",
    },
    Preset {
        id: "groq",
        name: "Groq",
        base_url: "https://api.groq.com/openai/v1",
        model: "openai/gpt-oss-120b",
        key_url: "https://console.groq.com/keys",
        free_tier: "Free, no card: 30 requests/min, 1,000/day, 8K tokens/min, 200K tokens/day. Very fast.",
        needs_key: true,
        vision: "meta-llama/llama-4-scout-17b-16e-instruct",
    },
    Preset {
        id: "openrouter",
        name: "OpenRouter (free models)",
        base_url: "https://openrouter.ai/api/v1",
        model: "meta-llama/llama-3.3-70b-instruct:free",
        key_url: "https://openrouter.ai/keys",
        free_tier: "Models ending in :free cost nothing: 20 requests/min, 50/day (1,000/day after a one-time $10 top-up).",
        needs_key: true,
        vision: "google/gemma-3-27b-it:free",
    },
    Preset {
        id: "cerebras",
        name: "Cerebras",
        base_url: "https://api.cerebras.ai/v1",
        model: "gpt-oss-120b",
        key_url: "https://cloud.cerebras.ai/",
        free_tier: "Free trial, no card: 5 requests/min, 1M tokens/day on gpt-oss-120b and Qwen.",
        needs_key: true,
        vision: "",
    },
    Preset {
        id: "mistral",
        name: "Mistral (Experiment plan)",
        base_url: "https://api.mistral.ai/v1",
        model: "mistral-small-latest",
        key_url: "https://console.mistral.ai/api-keys",
        free_tier: "Free Experiment plan with phone verification; generous monthly tokens. Prompts may be used for training.",
        needs_key: true,
        vision: "mistral-small-latest",
    },
    Preset {
        id: "github",
        name: "GitHub Models",
        base_url: "https://models.github.ai/inference",
        model: "openai/gpt-4.1-mini",
        key_url: "https://github.com/settings/personal-access-tokens",
        free_tier: "Free with a GitHub account: a token with the models:read permission. About 15 requests/min, 150 a day on most models.",
        needs_key: true,
        vision: "openai/gpt-4.1-mini",
    },
    Preset {
        id: "ollama",
        name: "Ollama (on this computer)",
        base_url: "http://localhost:11434/v1",
        model: "llama3.2",
        key_url: "https://ollama.com/download",
        free_tier: "Runs models locally: free, private, no limits; needs a decent CPU/GPU.",
        needs_key: false,
        vision: "",
    },
    Preset {
        id: "custom",
        name: "Other OpenAI-compatible",
        base_url: "",
        model: "",
        key_url: "",
        free_tier: "Any endpoint that speaks /chat/completions (OpenAI, DeepSeek, Together, LM Studio…).",
        needs_key: true,
        vision: "",
    },
];

pub fn preset(id: &str) -> &'static Preset {
    PRESETS.iter().find(|p| p.id == id).unwrap_or(&PRESETS[PRESETS.len() - 1])
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
pub struct AiConfig {
    pub provider: String,
    pub base_url: String,
    pub api_key: String,
    pub model: String,
    /// The model that looks at the channel's pictures: None = the provider's default, empty = don't look.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub vision_model: Option<String>,
}

impl AiConfig {
    pub fn ready(&self) -> bool {
        !self.base_url.trim().is_empty() && !self.model.trim().is_empty() && (!preset(&self.provider).needs_key || !self.api_key.trim().is_empty())
    }
    /// The model for pictures in use, empty when pictures are not looked at.
    pub fn vision(&self) -> &str {
        self.vision_model.as_deref().unwrap_or(preset(&self.provider).vision).trim()
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ChatMessage {
    pub role: String,
    pub content: String,
}

impl ChatMessage {
    pub fn system(content: impl Into<String>) -> Self {
        Self { role: "system".into(), content: content.into() }
    }
    pub fn user(content: impl Into<String>) -> Self {
        Self { role: "user".into(), content: content.into() }
    }
    pub fn assistant(content: impl Into<String>) -> Self {
        Self { role: "assistant".into(), content: content.into() }
    }
}

fn client() -> reqwest::Client {
    reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(15))
        .read_timeout(Duration::from_secs(120))
        .user_agent(concat!("GramalyzerStudio/", env!("CARGO_PKG_VERSION")))
        .build()
        .expect("HTTP client")
}

fn post(cfg: &AiConfig, path: &str) -> reqwest::RequestBuilder {
    let mut req = client().post(format!("{}{}", cfg.base_url.trim().trim_end_matches('/'), path));
    if !cfg.api_key.trim().is_empty() {
        req = req.bearer_auth(cfg.api_key.trim());
    }
    if cfg.provider == "openrouter" {
        req = req.header("HTTP-Referer", "https://github.com/Halty323/GramalyzerBot").header("X-Title", "Gramalyzer Studio");
    }
    req
}

/// What a provider said when it refused: its own error message when it sent one.
async fn failure(res: reqwest::Response) -> String {
    let status = res.status();
    let text = res.text().await.unwrap_or_default();
    let message = serde_json::from_str::<serde_json::Value>(&text)
        .ok()
        .and_then(|v| {
            let e = if v.is_array() { v[0]["error"].clone() } else { v["error"].clone() };
            e["message"].as_str().map(str::to_string).or_else(|| e.as_str().map(str::to_string))
        })
        .unwrap_or_else(|| text.chars().take(300).collect());
    match status.as_u16() {
        401 | 403 => format!("The provider rejected the API key ({status}). {message}"),
        429 => format!("Rate limit reached on the free tier ({status}); wait a minute or switch provider. {message}"),
        _ => format!("{status}: {message}"),
    }
}

/// Streams a chat completion: each item is the next piece of the answer, or the error that ended it.
pub fn stream_chat(cfg: AiConfig, messages: Vec<ChatMessage>, temperature: f32) -> impl Stream<Item = Result<String, String>> + Send + 'static {
    stream_json(cfg, serde_json::to_value(messages).unwrap_or_default(), temperature)
}

/// The same with messages as JSON, for ones whose content is more than text (pictures).
fn stream_json(cfg: AiConfig, messages: serde_json::Value, temperature: f32) -> impl Stream<Item = Result<String, String>> + Send + 'static {
    enum St {
        Start(AiConfig, serde_json::Value, f32),
        Reading(std::pin::Pin<Box<dyn Stream<Item = reqwest::Result<bytes::Bytes>> + Send>>, Vec<u8>),
        Done,
    }
    stream::unfold(St::Start(cfg, messages, temperature), |state| async move {
        let (mut body, mut buf) = match state {
            St::Done => return None,
            St::Start(cfg, messages, temperature) => {
                let req = post(&cfg, "/chat/completions").json(&serde_json::json!({
                    "model": cfg.model.trim(), "messages": messages, "stream": true, "temperature": temperature,
                }));
                match req.send().await {
                    Err(e) => return Some((Err(network(&e)), St::Done)),
                    Ok(res) if !res.status().is_success() => return Some((Err(failure(res).await), St::Done)),
                    Ok(res) => (Box::pin(res.bytes_stream()) as std::pin::Pin<Box<dyn Stream<Item = _> + Send>>, Vec::new()),
                }
            }
            St::Reading(body, buf) => (body, buf),
        };
        loop {
            // Answer every complete SSE line already buffered before reading more. The buffer holds bytes:
            // a chunk can end in the middle of a Cyrillic or Arabic character, but never in the middle of a line.
            while let Some(end) = buf.iter().position(|&b| b == b'\n') {
                let raw: Vec<u8> = buf.drain(..=end).collect();
                let line = String::from_utf8_lossy(&raw);
                let Some(data) = line.trim().strip_prefix("data:") else { continue };
                let data = data.trim();
                if data == "[DONE]" {
                    return Some((Ok(String::new()), St::Done));
                }
                let Ok(v) = serde_json::from_str::<serde_json::Value>(data) else { continue };
                if let Some(msg) = v["error"]["message"].as_str() {
                    return Some((Err(msg.to_string()), St::Done));
                }
                if let Some(piece) = v["choices"][0]["delta"]["content"].as_str() {
                    if !piece.is_empty() {
                        return Some((Ok(piece.to_string()), St::Reading(body, buf)));
                    }
                }
            }
            match body.next().await {
                Some(Ok(chunk)) => buf.extend_from_slice(&chunk),
                Some(Err(e)) => return Some((Err(network(&e)), St::Done)),
                None => return Some((Ok(String::new()), St::Done)),
            }
        }
    })
}

/// The whole answer at once, for actions that parse it (the week planner).
pub async fn complete(cfg: AiConfig, messages: Vec<ChatMessage>, temperature: f32) -> Result<String, String> {
    collect(stream_chat(cfg, messages, temperature)).await
}

async fn collect(s: impl Stream<Item = Result<String, String>>) -> Result<String, String> {
    let mut out = String::new();
    let mut s = Box::pin(s);
    while let Some(piece) = s.next().await {
        out.push_str(&piece?);
    }
    if out.trim().is_empty() { Err("The model returned an empty answer.".into()) } else { Ok(out) }
}

/// Pictures larger than this go to the provider as their address instead of their bytes.
const INLINE_MAX: usize = 3 << 20;

/// What the pictures at `urls` show, from the model for pictures: each is downloaded and sent with the prompt, or
/// sent as its address when it can't be downloaded here (the provider fetches it then).
pub async fn look(cfg: AiConfig, prompt: String, urls: Vec<String>) -> Result<String, String> {
    use base64::Engine;
    let mut content = vec![serde_json::json!({"type": "text", "text": prompt})];
    for url in urls {
        let bytes = async {
            let res = client().get(&url).send().await.ok().filter(|r| r.status().is_success())?;
            let kind = res.headers().get(reqwest::header::CONTENT_TYPE).and_then(|v| v.to_str().ok()).filter(|t| t.starts_with("image/")).unwrap_or("image/jpeg").to_string();
            res.bytes().await.ok().filter(|b| b.len() <= INLINE_MAX).map(|b| (kind, b))
        }
        .await;
        let src = match bytes {
            Some((kind, b)) => format!("data:{kind};base64,{}", base64::engine::general_purpose::STANDARD.encode(&b)),
            None => url,
        };
        content.push(serde_json::json!({"type": "image_url", "image_url": {"url": src}}));
    }
    let model = cfg.vision().to_string();
    collect(stream_json(AiConfig { model, ..cfg }, serde_json::json!([{"role": "user", "content": content}]), 0.3)).await.map(|s| s.trim().to_string())
}

/// The model ids the endpoint offers (GET /models), to pick from in settings.
pub async fn models(cfg: AiConfig) -> Result<Vec<String>, String> {
    let mut req = client().get(format!("{}/models", cfg.base_url.trim().trim_end_matches('/')));
    if !cfg.api_key.trim().is_empty() {
        req = req.bearer_auth(cfg.api_key.trim());
    }
    let res = req.send().await.map_err(|e| network(&e))?;
    if !res.status().is_success() {
        return Err(failure(res).await);
    }
    let v: serde_json::Value = res.json().await.map_err(|e| e.to_string())?;
    let mut ids: Vec<String> = v["data"]
        .as_array()
        .or_else(|| v.as_array())
        .map(|a| a.iter().filter_map(|m| m["id"].as_str().map(|s| s.trim_start_matches("models/").to_string())).collect())
        .unwrap_or_default();
    ids.sort();
    ids.dedup();
    Ok(ids)
}

fn network(e: &reqwest::Error) -> String {
    if e.is_timeout() { "The AI provider took too long to answer.".into() } else { format!("Could not reach the AI provider: {}", with_causes(e)) }
}

/// reqwest's message alone ("error sending request for url …") hides the reason; add the chain of causes.
pub fn with_causes(e: &dyn std::error::Error) -> String {
    let mut out = e.to_string();
    let mut cause = e.source();
    while let Some(c) = cause {
        let text = c.to_string();
        if !out.contains(&text) {
            out = format!("{out}: {text}");
        }
        cause = c.source();
    }
    out
}

/// One idea the week planner returns.
#[derive(Debug, Clone, PartialEq, Deserialize)]
pub struct PlannedPost {
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub body: String,
    #[serde(default)]
    pub category: String,
    /// For a channel of pictures: what the post's picture should show.
    #[serde(default)]
    pub picture: String,
}

/// The JSON array in a model's answer, even when it wrapped it in prose or a ```json fence.
pub fn parse_plan(answer: &str) -> Result<Vec<PlannedPost>, String> {
    let start = answer.find('[').ok_or("The model did not return a list of posts.")?;
    let end = answer.rfind(']').ok_or("The model did not return a list of posts.")?;
    if end <= start {
        return Err("The model did not return a list of posts.".into());
    }
    let posts: Vec<PlannedPost> = serde_json::from_str(&answer[start..=end]).map_err(|e| format!("Could not read the plan: {e}"))?;
    // A picture post may have no caption at all.
    let posts: Vec<_> = posts.into_iter().filter(|p| !p.body.trim().is_empty() || !p.picture.trim().is_empty()).collect();
    if posts.is_empty() { Err("The plan was empty.".into()) } else { Ok(posts) }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plan_in_a_fence() {
        let answer = "Here you go:\n```json\n[{\"title\":\"A\",\"body\":\"Hello\",\"category\":\"Tips\"},{\"title\":\"B\",\"body\":\"\"},{\"title\":\"C\",\"picture\":\"a cat in a box\",\"body\":\"\"}]\n```";
        let plan = parse_plan(answer).unwrap();
        assert_eq!(plan.len(), 2);
        assert_eq!(plan[0].title, "A");
        assert_eq!(plan[1].picture, "a cat in a box");
    }

    #[test]
    fn presets_resolve() {
        assert_eq!(preset("groq").base_url, "https://api.groq.com/openai/v1");
        assert_eq!(preset("nope").id, "custom");
        let cfg = AiConfig { provider: "ollama".into(), base_url: "http://localhost:11434/v1".into(), api_key: String::new(), model: "llama3.2".into(), vision_model: None };
        assert!(cfg.ready());
        // the provider's model for pictures until one is chosen, and none when it is cleared
        let groq = AiConfig { provider: "groq".into(), ..cfg.clone() };
        assert_eq!(groq.vision(), "meta-llama/llama-4-scout-17b-16e-instruct");
        assert_eq!(AiConfig { vision_model: Some(" ".into()), ..groq }.vision(), "");
        assert_eq!(cfg.vision(), "");
    }
}
