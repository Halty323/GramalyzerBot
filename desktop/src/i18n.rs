//! The app's twelve languages, the same ones as the bot and the website. Strings live in locales/<code>.json
//! (compiled in); a key a language lacks falls back to English. Counted phrases are objects with one form per
//! CLDR plural category; `{n}` is the formatted count and `{name}`-style placeholders are filled by `tv`.
use chrono::{DateTime, Datelike, Local, Timelike};
use serde_json::{Map, Value};
use std::sync::LazyLock;

pub struct Lang {
    pub code: &'static str,
    pub name: &'static str,
    pub rtl: bool,
    source: &'static str,
}

pub const LANGS: &[Lang] = &[
    Lang { code: "en", name: "English", rtl: false, source: include_str!("../locales/en.json") },
    Lang { code: "ru", name: "Русский", rtl: false, source: include_str!("../locales/ru.json") },
    Lang { code: "uk", name: "Українська", rtl: false, source: include_str!("../locales/uk.json") },
    Lang { code: "hi", name: "हिन्दी", rtl: false, source: include_str!("../locales/hi.json") },
    Lang { code: "id", name: "Bahasa Indonesia", rtl: false, source: include_str!("../locales/id.json") },
    Lang { code: "pt", name: "Português", rtl: false, source: include_str!("../locales/pt.json") },
    Lang { code: "es", name: "Español", rtl: false, source: include_str!("../locales/es.json") },
    Lang { code: "ar", name: "العربية", rtl: true, source: include_str!("../locales/ar.json") },
    Lang { code: "fa", name: "فارسی", rtl: true, source: include_str!("../locales/fa.json") },
    Lang { code: "uz", name: "Oʻzbekcha", rtl: false, source: include_str!("../locales/uz.json") },
    Lang { code: "tr", name: "Türkçe", rtl: false, source: include_str!("../locales/tr.json") },
    Lang { code: "vi", name: "Tiếng Việt", rtl: false, source: include_str!("../locales/vi.json") },
];

static TABLES: LazyLock<Vec<Map<String, Value>>> = LazyLock::new(|| {
    LANGS
        .iter()
        .map(|l| match serde_json::from_str::<Value>(l.source) {
            Ok(Value::Object(m)) => m,
            _ => panic!("locales/{}.json is not a JSON object", l.code),
        })
        .collect()
});

/// The system's language if we speak it (or the one its speakers most often read Telegram in), else English.
pub fn detect() -> &'static str {
    let tag = std::env::var("LC_ALL").or_else(|_| std::env::var("LC_MESSAGES")).or_else(|_| std::env::var("LANG")).unwrap_or_default();
    let base = tag.to_lowercase().split(['_', '-', '.']).next().unwrap_or("").to_string();
    let base = match base.as_str() {
        "be" | "kk" | "ky" | "tg" => "ru",
        "ms" => "id",
        other => other,
    };
    LANGS.iter().find(|l| l.code == base).map(|l| l.code).unwrap_or("en")
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct I18n {
    index: usize,
}

impl I18n {
    pub fn new(code: &str) -> Self {
        Self { index: LANGS.iter().position(|l| l.code == code).unwrap_or(0) }
    }
    pub fn lang(&self) -> &'static Lang {
        &LANGS[self.index]
    }
    pub fn rtl(&self) -> bool {
        self.lang().rtl
    }

    fn raw(&self, key: &str) -> Option<&'static Value> {
        TABLES[self.index].get(key).or_else(|| TABLES[0].get(key))
    }

    /// The text of key; the key itself when no language has it, so a missing string is easy to spot.
    pub fn t(&self, key: &str) -> String {
        match self.raw(key) {
            Some(Value::String(s)) => s.clone(),
            Some(Value::Object(m)) => m.get("other").and_then(Value::as_str).unwrap_or(key).to_string(),
            _ => key.to_string(),
        }
    }

    pub fn has(&self, key: &str) -> bool {
        TABLES[0].contains_key(key)
    }

    /// The text of key with {name} placeholders filled.
    pub fn tv(&self, key: &str, vars: &[(&str, &str)]) -> String {
        fill(self.t(key), vars)
    }

    /// A counted phrase: the plural form for n, with {n} as the formatted number.
    pub fn tn(&self, key: &str, n: i64) -> String {
        self.tnv(key, n, &[])
    }

    pub fn tnv(&self, key: &str, n: i64, vars: &[(&str, &str)]) -> String {
        let text = match self.raw(key) {
            Some(Value::Object(m)) => {
                let cat = plural(self.lang().code, n);
                m.get(cat).or_else(|| m.get("other")).and_then(Value::as_str).unwrap_or(key).to_string()
            }
            Some(Value::String(s)) => s.clone(),
            _ => key.to_string(),
        };
        let formatted = self.num(n);
        let mut all: Vec<(&str, &str)> = vec![("n", formatted.as_str())];
        all.extend_from_slice(vars);
        fill(text, &all)
    }

    fn separators(&self) -> (&'static str, &'static str) {
        match self.lang().code {
            "en" | "hi" | "ar" => (",", "."),
            "ru" | "uk" | "uz" | "fa" => ("\u{202F}", ","),
            _ => (".", ","),
        }
    }

    pub fn num(&self, n: i64) -> String {
        let (group, _) = self.separators();
        let digits = n.unsigned_abs().to_string();
        let mut out = String::new();
        for (i, c) in digits.chars().enumerate() {
            if i > 0 && (digits.len() - i) % 3 == 0 {
                out.push_str(group);
            }
            out.push(c);
        }
        if n < 0 { format!("−{out}") } else { out }
    }

    pub fn dec(&self, x: f64, places: usize) -> String {
        let (_, point) = self.separators();
        let s = format!("{:.*}", places, x.abs());
        let (int, frac) = s.split_once('.').unwrap_or((&s, ""));
        let int = self.num(int.parse().unwrap_or(0));
        let sign = if x < 0.0 && s.chars().any(|c| c.is_ascii_digit() && c != '0') { "−" } else { "" };
        if frac.is_empty() { format!("{sign}{int}") } else { format!("{sign}{int}{point}{frac}") }
    }

    /// 1234 → 1.2K, 3400000 → 3.4M.
    pub fn compact(&self, x: f64) -> String {
        let a = x.abs();
        if a >= 1e6 { format!("{}M", self.dec(x / 1e6, if a >= 1e7 { 0 } else { 1 })) } else if a >= 1e4 { format!("{}K", self.dec(x / 1e3, 0)) } else if a >= 1e3 { format!("{}K", self.dec(x / 1e3, 1)) } else { self.dec(x, 0) }
    }

    pub fn signed(&self, n: i64) -> String {
        if n > 0 { format!("+{}", self.num(n)) } else { self.num(n) }
    }

    pub fn percent(&self, x: f64, places: usize) -> String {
        format!("{}%", self.dec(x, places))
    }

    fn list(&self, key: &str, i: usize) -> String {
        self.raw(key).and_then(|v| v.as_array()).and_then(|a| a.get(i)).and_then(Value::as_str).unwrap_or("?").to_string()
    }
    /// Monday = 0.
    pub fn weekday(&self, i: usize) -> String {
        self.list("_weekdays", i)
    }
    pub fn weekday_short(&self, i: usize) -> String {
        self.list("_weekdays_short", i)
    }
    pub fn month_short(&self, i: usize) -> String {
        self.list("_months_short", i)
    }

    /// "21 Oct", in this language's order.
    pub fn day_month(&self, d: &DateTime<Local>) -> String {
        fill(self.t("_day_month"), &[("d", &d.day().to_string()), ("m", &self.month_short(d.month0() as usize))])
    }
    pub fn time(&self, d: &DateTime<Local>) -> String {
        format!("{:02}:{:02}", d.hour(), d.minute())
    }
    /// "Tue 21 Oct, 18:00".
    pub fn when(&self, d: &DateTime<Local>) -> String {
        let comma = if self.lang().rtl { "،" } else { "," };
        format!("{} {}{comma} {}", self.weekday_short(d.weekday().num_days_from_monday() as usize), self.day_month(d), self.time(d))
    }
    /// "3 h ago", "yesterday", or a date.
    pub fn ago(&self, d: &DateTime<Local>) -> String {
        let mins = (Local::now() - *d).num_minutes();
        if mins < 1 {
            self.t("time.now")
        } else if mins < 60 {
            self.tn("time.min_ago", mins)
        } else if mins < 24 * 60 {
            self.tn("time.h_ago", mins / 60)
        } else if mins < 7 * 24 * 60 {
            self.tn("time.d_ago", mins / 1440)
        } else {
            self.day_month(d)
        }
    }
}

fn fill(text: String, vars: &[(&str, &str)]) -> String {
    let mut out = text;
    for (k, v) in vars {
        out = out.replace(&format!("{{{k}}}"), v);
    }
    out
}

/// The CLDR plural category of an integer n in lang.
pub fn plural(lang: &str, n: i64) -> &'static str {
    let n = n.unsigned_abs();
    let (m10, m100) = (n % 10, n % 100);
    match lang {
        "ru" | "uk" => {
            if m10 == 1 && m100 != 11 {
                "one"
            } else if (2..=4).contains(&m10) && !(12..=14).contains(&m100) {
                "few"
            } else {
                "many"
            }
        }
        "ar" => match n {
            0 => "zero",
            1 => "one",
            2 => "two",
            _ if (3..=10).contains(&m100) => "few",
            _ if (11..=99).contains(&m100) => "many",
            _ => "other",
        },
        "hi" | "fa" | "pt" => if n <= 1 { "one" } else { "other" },
        "id" | "vi" => "other",
        _ => if n == 1 { "one" } else { "other" },
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeSet;

    fn placeholders(v: &Value) -> BTreeSet<String> {
        let mut out = BTreeSet::new();
        let texts: Vec<&str> = match v {
            Value::String(s) => vec![s.as_str()],
            Value::Object(m) => m.values().filter_map(Value::as_str).collect(),
            Value::Array(_) => vec![],
            _ => vec![],
        };
        for t in texts {
            let mut rest = t;
            while let Some(i) = rest.find('{') {
                let Some(j) = rest[i..].find('}') else { break };
                out.insert(rest[i + 1..i + j].to_string());
                rest = &rest[i + j + 1..];
            }
        }
        out.remove("n");
        out
    }

    /// Every language has every English key, the same placeholders, and the plural forms its rules need.
    #[test]
    fn locales_are_complete() {
        let en = &TABLES[0];
        let mut problems = vec![];
        for (i, lang) in LANGS.iter().enumerate().skip(1) {
            let table = &TABLES[i];
            for (key, value) in en {
                let Some(theirs) = table.get(key) else {
                    problems.push(format!("{}: missing {key}", lang.code));
                    continue;
                };
                if placeholders(value) != placeholders(theirs) {
                    problems.push(format!("{}: placeholders differ in {key}", lang.code));
                }
                if value.is_object() != theirs.is_object() || value.is_array() != theirs.is_array() {
                    problems.push(format!("{}: {key} has the wrong shape", lang.code));
                }
                if let (Value::Object(_), Value::Object(m)) = (value, theirs) {
                    for n in [0, 1, 2, 3, 5, 11, 21, 22, 25, 101, 111] {
                        let cat = plural(lang.code, n);
                        if !m.contains_key(cat) && !m.contains_key("other") {
                            problems.push(format!("{}: {key} lacks the {cat} form", lang.code));
                        }
                    }
                }
                if let (Value::Array(a), Value::Array(b)) = (value, theirs) {
                    if a.len() != b.len() {
                        problems.push(format!("{}: {key} has {} items", lang.code, b.len()));
                    }
                }
            }
            for key in table.keys() {
                if !en.contains_key(key) {
                    problems.push(format!("{}: unknown key {key}", lang.code));
                }
            }
        }
        assert!(problems.is_empty(), "{}", problems.join("\n"));
    }

    #[test]
    fn plurals_and_numbers() {
        assert_eq!(plural("ru", 21), "one");
        assert_eq!(plural("uk", 3), "few");
        assert_eq!(plural("ru", 11), "many");
        assert_eq!(plural("ar", 0), "zero");
        assert_eq!(plural("ar", 105), "few");
        let en = I18n::new("en");
        assert_eq!(en.num(1234567), "1,234,567");
        assert_eq!(en.dec(-1.5, 1), "−1.5");
        assert_eq!(en.compact(12_345.0), "12K");
        assert_eq!(I18n::new("de").lang().code, "en");
    }
}
