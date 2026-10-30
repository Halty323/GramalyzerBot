//! The Gramalyzer backend's REST API (everything under /api), signed in with the workspace token as a Bearer header.
use crate::model::*;
use reqwest::{Method, StatusCode};
use serde::de::DeserializeOwned;
use std::time::Duration;

/// A failed call. `code` is the translatable error code /api/tracked sends ("not_found", "limit", …).
#[derive(Debug, Clone, PartialEq)]
pub struct ApiError {
    pub status: u16,
    pub message: String,
    pub code: Option<String>,
}

impl ApiError {
    fn network(e: reqwest::Error) -> Self {
        Self { status: 0, message: crate::ai::with_causes(&e), code: None }
    }
    pub fn unauthorized(&self) -> bool {
        self.status == 401
    }
}

pub type Result<T> = std::result::Result<T, ApiError>;

#[derive(Debug, Clone)]
pub struct Client {
    base: String,
    token: String,
    http: reqwest::Client,
}

impl Client {
    pub fn new(server: &str, token: &str) -> Self {
        let http = reqwest::Client::builder()
            .timeout(Duration::from_secs(40))
            .user_agent(concat!("GramalyzerStudio/", env!("CARGO_PKG_VERSION")))
            .build()
            .expect("HTTP client");
        Self { base: server.trim().trim_end_matches('/').to_string(), token: token.trim().to_string(), http }
    }

    /// A path under the server, for links the backend returns relative (photo URLs).
    pub fn url(&self, path: &str) -> String {
        if path.starts_with("http://") || path.starts_with("https://") { path.to_string() } else { format!("{}{}", self.base, path) }
    }

    fn request(&self, method: Method, path: &str) -> reqwest::RequestBuilder {
        let r = self.http.request(method, format!("{}/api{}", self.base, path));
        if self.token.is_empty() { r } else { r.bearer_auth(&self.token) }
    }

    async fn send<T: DeserializeOwned>(&self, req: reqwest::RequestBuilder) -> Result<T> {
        let res = req.send().await.map_err(ApiError::network)?;
        let status = res.status();
        if !status.is_success() {
            let body: serde_json::Value = res.json().await.unwrap_or_default();
            return Err(ApiError {
                status: status.as_u16(),
                message: body["error"].as_str().unwrap_or("").to_string(),
                code: body["code"].as_str().map(str::to_string),
            });
        }
        if status == StatusCode::NO_CONTENT {
            return serde_json::from_value(serde_json::Value::Null).map_err(|e| ApiError { status: 0, message: e.to_string(), code: None });
        }
        res.json().await.map_err(|e| ApiError { status: status.as_u16(), message: format!("Unexpected answer from the server: {e}"), code: None })
    }

    async fn get<T: DeserializeOwned>(&self, path: &str) -> Result<T> {
        self.send(self.request(Method::GET, path)).await
    }

    pub async fn workspace(self) -> Result<Workspace> {
        self.get("/workspace").await
    }
    /// Asks the server to bring every managed and watched channel up to date (it skips this if it just did).
    pub async fn refresh_all(self, reason: &'static str) -> Result<RefreshStatus> {
        self.send(self.request(Method::POST, &format!("/refresh?reason={reason}"))).await
    }
    pub async fn refresh_status(self) -> Result<RefreshStatus> {
        self.get("/refresh").await
    }
    pub async fn insights(self, id: String) -> Result<Insights> {
        self.get(&format!("/channels/{}/insights", enc(&id))).await
    }
    pub async fn feed(self, id: String) -> Result<ChannelFeed> {
        self.get(&format!("/channels/{}/feed?tz={}", enc(&id), enc(&zone()))).await
    }
    pub async fn save_post(self, id: Option<String>, input: PostInput) -> Result<Post> {
        let req = match &id {
            Some(id) => self.request(Method::PATCH, &format!("/posts/{}", enc(id))),
            None => self.request(Method::POST, "/posts"),
        };
        self.send(req.json(&input)).await
    }
    pub async fn delete_post(self, id: String) -> Result<()> {
        self.send::<Option<()>>(self.request(Method::DELETE, &format!("/posts/{}", enc(&id)))).await.map(|_| ())
    }
    pub async fn channel_settings(self, id: String, settings: ChannelSettings) -> Result<Channel> {
        self.send(self.request(Method::PATCH, &format!("/channels/{}", enc(&id))).json(&settings)).await
    }
    pub async fn tracked(self) -> Result<Vec<TrackedSummary>> {
        self.get(&format!("/tracked?tz={}", enc(&zone()))).await
    }
    pub async fn lookup(self, username: String) -> Result<TrackedDetail> {
        self.get(&format!("/tracked/{}?tz={}", enc(&username), enc(&zone()))).await
    }
    pub async fn track(self, username: String) -> Result<TrackedDetail> {
        let req = self.request(Method::POST, &format!("/tracked?tz={}", enc(&zone()))).json(&serde_json::json!({ "username": username }));
        self.send(req).await
    }
    pub async fn refresh(self, username: String) -> Result<TrackedDetail> {
        self.send(self.request(Method::POST, &format!("/tracked/{}/refresh?tz={}", enc(&username), enc(&zone())))).await
    }
    pub async fn untrack(self, username: String) -> Result<()> {
        self.send::<Option<()>>(self.request(Method::DELETE, &format!("/tracked/{}", enc(&username)))).await.map(|_| ())
    }
    /// An image the backend serves (a channel's photo); None when there is none.
    pub async fn image(self, url: String) -> Option<Vec<u8>> {
        let req = if url.starts_with(&self.base) || url.starts_with('/') {
            let r = self.http.get(self.url(&url));
            if self.token.is_empty() { r } else { r.bearer_auth(&self.token) }
        } else {
            self.http.get(url)
        };
        let res = req.send().await.ok()?;
        if !res.status().is_success() {
            return None;
        }
        res.bytes().await.ok().map(|b| b.to_vec())
    }
}

/// The computer's IANA timezone, so posting hours the backend counts line up with the user's clock.
pub fn zone() -> String {
    iana_zone().unwrap_or_else(|| "UTC".into())
}

fn iana_zone() -> Option<String> {
    if let Ok(tz) = std::env::var("TZ") {
        if tz.parse::<chrono_tz::Tz>().is_ok() {
            return Some(tz);
        }
    }
    #[cfg(unix)]
    {
        if let Ok(link) = std::fs::read_link("/etc/localtime") {
            let s = link.to_string_lossy();
            if let Some(i) = s.find("zoneinfo/") {
                return Some(s[i + 9..].to_string());
            }
        }
        if let Ok(s) = std::fs::read_to_string("/etc/timezone") {
            return Some(s.trim().to_string());
        }
    }
    None
}

fn enc(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => out.push(b as char),
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

/// "@durov", "t.me/durov", "https://t.me/s/durov/123" → "durov"; None when it can't be a public channel username.
pub fn username_from(input: &str) -> Option<String> {
    let mut s = input.trim();
    for prefix in ["https://", "http://"] {
        s = s.strip_prefix(prefix).unwrap_or(s);
    }
    s = s.strip_prefix("www.").unwrap_or(s);
    for host in ["t.me/s/", "t.me/", "telegram.me/", "telegram.dog/", "tg://resolve?domain="] {
        if let Some(rest) = s.strip_prefix(host) {
            s = rest;
            break;
        }
    }
    let s = s.strip_prefix('@').unwrap_or(s);
    let name: String = s.chars().take_while(|c| c.is_ascii_alphanumeric() || *c == '_').collect();
    let rest = &s[name.len()..];
    let ok = (4..=32).contains(&name.len()) && name.chars().next().is_some_and(|c| c.is_ascii_alphabetic()) && (rest.is_empty() || rest.starts_with(['/', '?', '#', '&']));
    ok.then_some(name)
}

#[cfg(test)]
mod tests {
    use super::username_from;

    #[test]
    fn usernames() {
        assert_eq!(username_from("@durov").as_deref(), Some("durov"));
        assert_eq!(username_from("https://t.me/s/durov/123").as_deref(), Some("durov"));
        assert_eq!(username_from("t.me/durov").as_deref(), Some("durov"));
        assert_eq!(username_from("tg://resolve?domain=durov").as_deref(), Some("durov"));
        assert_eq!(username_from("abc"), None);
        assert_eq!(username_from("hello world"), None);
        assert_eq!(username_from("1durov"), None);
    }
}
