package com.halty.gramalyzerbot.i18n;

import com.halty.gramalyzerbot.user.User;

import java.util.Locale;
import java.util.Optional;

/**
 * The languages the bot speaks: English plus the languages of Telegram's biggest audiences. Each one has a bundle at
 * {@code i18n/bot_<code>.properties}; missing keys fall back to English.
 */
public enum Lang {
    EN("en", "🇬🇧", "English"),
    RU("ru", "🇷🇺", "Русский"),
    UK("uk", "🇺🇦", "Українська"),
    HI("hi", "🇮🇳", "हिन्दी"),
    ID("id", "🇮🇩", "Bahasa Indonesia"),
    PT("pt", "🇧🇷", "Português"),
    ES("es", "🇪🇸", "Español"),
    AR("ar", "🇸🇦", "العربية"),
    FA("fa", "🇮🇷", "فارسی"),
    UZ("uz", "🇺🇿", "Oʻzbekcha"),
    TR("tr", "🇹🇷", "Türkçe"),
    VI("vi", "🇻🇳", "Tiếng Việt");

    private final String code;
    private final String flag;
    private final String nativeName;
    private final Locale locale;

    Lang(String code, String flag, String nativeName) {
        this.code = code;
        this.flag = flag;
        this.nativeName = nativeName;
        this.locale = Locale.forLanguageTag(code);
    }

    public String code() { return code; }

    public String flag() { return flag; }

    public String nativeName() { return nativeName; }

    public Locale locale() { return locale; }

    public String label() { return flag + " " + nativeName; }

    /** "pt-br" → PT; unknown or missing → empty. Neighbours that mostly read Russian get Russian. */
    public static Optional<Lang> fromCode(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        String base = code.toLowerCase(Locale.ROOT).split("[-_]")[0];
        for (Lang l : values()) if (l.code.equals(base)) return Optional.of(l);
        return switch (base) {
            case "be", "kk", "ky", "tg" -> Optional.of(RU);
            case "ms" -> Optional.of(ID);
            default -> Optional.empty();
        };
    }

    /** The person's own choice, else their Telegram app's language, else English. */
    public static Lang of(User u) {
        if (u == null) return EN;
        return fromCode(u.getLanguage()).or(() -> fromCode(u.getLanguageCode())).orElse(EN);
    }
}
