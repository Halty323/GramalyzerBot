package com.halty.gramalyzerbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * @param token              bot token from @BotFather (set TELEGRAM_BOT_TOKEN, never commit it)
 * @param admins             bot administrators: Telegram @usernames and/or numeric user ids
 *                           (BOT_ADMINS=@halty,123456789); see {@link #isAdmin}
 * @param publicRegistration initial value of "anyone may connect a channel"; changeable later from /admin
 * @param defaultTimezone    timezone given to newly connected channels until the owner sets one
 */
@ConfigurationProperties(prefix = "telegram.bot")
public record BotProperties(String token, List<String> admins, Boolean publicRegistration, String defaultTimezone) {

    /** Telegram usernames: 5–32 characters, letters, digits and underscores. */
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{5,32}");
    private static final Pattern USER_ID = Pattern.compile("[0-9]{1,19}");

    public BotProperties {
        List<String> clean = new ArrayList<>();
        if (admins != null) {
            for (String raw : admins) {
                if (raw == null || raw.isBlank()) continue;
                String entry = raw.trim();
                if (normalizeHandle(entry) == null && !USER_ID.matcher(entry).matches())
                    throw new IllegalArgumentException("BOT_ADMINS: \"" + entry
                            + "\" is neither a Telegram username (like @halty) nor a numeric user id");
                clean.add(entry);
            }
        }
        admins = List.copyOf(clean);
        if (publicRegistration == null) publicRegistration = true;
        if (defaultTimezone == null || defaultTimezone.isBlank()) defaultTimezone = "UTC";
    }

    /** The configured numeric user ids. */
    public Set<Long> adminIds() {
        Set<Long> ids = new LinkedHashSet<>();
        for (String a : admins) if (USER_ID.matcher(a).matches()) ids.add(Long.parseLong(a));
        return ids;
    }

    /** The configured usernames, lower-case and without "@". */
    public Set<String> adminHandles() {
        Set<String> handles = new LinkedHashSet<>();
        for (String a : admins) {
            String h = normalizeHandle(a);
            if (h != null) handles.add(h);
        }
        return handles;
    }

    /**
     * Whether this person is listed as a bot administrator, by user id or by @username (case-insensitive).
     * A username only matches while the person still uses it.
     */
    public boolean isAdmin(long telegramId, String username) {
        if (adminIds().contains(telegramId)) return true;
        String h = username == null ? null : normalizeHandle(username);
        return h != null && adminHandles().contains(h);
    }

    /** "@Halty", "halty" or "https://t.me/halty" → "halty"; null if it isn't a username (e.g. a numeric id). */
    static String normalizeHandle(String s) {
        String h = s.trim().replaceFirst("^(?i)(https?://)?(t\\.me/)", "").replaceFirst("^@", "");
        if (!USERNAME.matcher(h).matches() || h.chars().allMatch(Character::isDigit)) return null;
        return h.toLowerCase(Locale.ROOT);
    }
}
