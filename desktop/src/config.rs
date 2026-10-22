//! What the app remembers between runs, as JSON in the platform's config folder
//! (~/.config/gramalyzerstudio, %APPDATA%\Gramalyzer\Gramalyzer Studio\config, ~/Library/Application Support/dev.Gramalyzer.Gramalyzer-Studio).
use crate::ai::AiConfig;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::PathBuf;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Config {
    pub server: String,
    pub token: String,
    pub lang: String,
    pub dark: Option<bool>,
    pub ai: AiConfig,
    /// The voice the AI writes in, applied to every request ("friendly, short sentences, no emoji").
    pub brand_voice: String,
    /// The language AI drafts are written in when the channel's own isn't obvious; empty = the interface language.
    pub content_lang: String,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            server: "http://localhost:8080".into(),
            token: String::new(),
            lang: String::new(),
            dark: None,
            ai: AiConfig { provider: "groq".into(), base_url: crate::ai::preset("groq").base_url.into(), api_key: String::new(), model: crate::ai::preset("groq").model.into(), vision_model: None },
            brand_voice: String::new(),
            content_lang: String::new(),
        }
    }
}

fn path() -> Option<PathBuf> {
    if let Ok(p) = std::env::var("GRAMALYZER_STUDIO_CONFIG") {
        return Some(PathBuf::from(p));
    }
    directories::ProjectDirs::from("dev", "Gramalyzer", "Gramalyzer Studio").map(|d| d.config_dir().join("config.json"))
}

impl Config {
    pub fn load() -> Self {
        let mut config: Config = path().and_then(|p| std::fs::read_to_string(p).ok()).and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default();
        // Environment overrides, handy for a first run against a fresh server.
        if let Ok(s) = std::env::var("GRAMALYZER_SERVER") {
            config.server = s;
        }
        if let Ok(t) = std::env::var("GRAMALYZER_API_TOKEN") {
            config.token = t;
        }
        config
    }

    /// Saves in the background; the file holds tokens, so on Unix only the user can read it.
    pub async fn save(self) -> Result<(), String> {
        write(path(), serde_json::to_string_pretty(&self).map_err(|e| e.to_string())?).await
    }
}

async fn write(path: Option<PathBuf>, json: String) -> Result<(), String> {
    let Some(path) = path else { return Err("No config folder on this system.".into()) };
    if let Some(dir) = path.parent() {
        tokio::fs::create_dir_all(dir).await.map_err(|e| e.to_string())?;
    }
    tokio::fs::write(&path, json).await.map_err(|e| e.to_string())?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = tokio::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600)).await;
    }
    Ok(())
}

/// What the model for pictures made of a channel's pictures, kept in looks.json beside the config so a start
/// doesn't ask again.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SavedLook {
    /// The endpoint and model that looked: another model looks again.
    pub key: String,
    /// When, in seconds since 1970.
    pub at: i64,
    #[serde(default)]
    pub text: Option<String>,
    #[serde(default)]
    pub error: Option<String>,
}

fn looks_path() -> Option<PathBuf> {
    path().map(|p| p.with_file_name("looks.json"))
}

pub fn load_looks() -> HashMap<String, SavedLook> {
    looks_path().and_then(|p| std::fs::read_to_string(p).ok()).and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default()
}

pub async fn save_looks(looks: HashMap<String, SavedLook>) -> Result<(), String> {
    write(looks_path(), serde_json::to_string_pretty(&looks).map_err(|e| e.to_string())?).await
}
