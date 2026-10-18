package com.halty.gramalyzerbot.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * The bot's texts. Each update (or each recipient of a scheduled message) is handled in one language, set with
 * {@link #use} and read by every screen through {@link #t}; that keeps a {@code Locale} out of hundreds of method
 * signatures. Texts are Telegram HTML with {@code {0}}-style placeholders; arguments are inserted as given, so escape
 * user-supplied text with {@code Fmt.esc} first.
 */
public final class Tr {

    private static final Map<Lang, Properties> BUNDLES = new EnumMap<>(Lang.class);
    private static final ThreadLocal<Lang> CURRENT = ThreadLocal.withInitial(() -> Lang.EN);

    static {
        for (Lang l : Lang.values()) BUNDLES.put(l, load(l));
    }

    private Tr() {}

    private static Properties load(Lang l) {
        Properties p = new Properties();
        try (InputStream in = Tr.class.getResourceAsStream("/i18n/bot_" + l.code() + ".properties")) {
            if (in != null) p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    public static void use(Lang lang) { CURRENT.set(lang == null ? Lang.EN : lang); }

    public static Lang lang() { return CURRENT.get(); }

    public static Locale locale() { return CURRENT.get().locale(); }

    /** Runs {@code work} in another language and switches back afterwards. */
    public static <T> T in(Lang lang, Supplier<T> work) {
        Lang before = CURRENT.get();
        use(lang);
        try {
            return work.get();
        } finally {
            use(before);
        }
    }

    /** The text for {@code key} in the current language (English if it has none), with {0}, {1}… filled in. */
    public static String t(String key, Object... args) { return format(raw(CURRENT.get(), key), args); }

    /** Whether the current language (or English) has this key. */
    public static boolean has(String key) {
        return BUNDLES.get(CURRENT.get()).containsKey(key) || BUNDLES.get(Lang.EN).containsKey(key);
    }

    /**
     * A counted phrase: {@code key.one}, {@code key.few}, {@code key.many}… by the language's plural rules, falling back
     * to {@code key.other}. {0} is the formatted count, further arguments follow as {1}, {2}…
     */
    public static String n(String key, long count, Object... more) {
        Lang l = CURRENT.get();
        String category = Plurals.category(l, count);
        String text = find(l, key + "." + category);
        if (text == null) text = find(l, key + ".other");
        if (text == null) text = raw(l, key + ".one");
        Object[] args = new Object[more.length + 1];
        args[0] = String.format(Locale.US, "%,d", count);
        System.arraycopy(more, 0, args, 1, more.length);
        return format(text, args);
    }

    /** The same key in every language, e.g. to recognise a keyboard button whatever language it was sent in. */
    public static Map<Lang, String> all(String key) {
        Map<Lang, String> out = new EnumMap<>(Lang.class);
        for (Lang l : Lang.values()) out.put(l, raw(l, key));
        return out;
    }

    private static String find(Lang l, String key) {
        String v = BUNDLES.get(l).getProperty(key);
        return v != null ? v : BUNDLES.get(Lang.EN).getProperty(key);
    }

    private static String raw(Lang l, String key) {
        String v = find(l, key);
        return v != null ? v : key;
    }

    static String format(String text, Object... args) {
        if (args.length == 0 || text.indexOf('{') < 0) return text;
        StringBuilder sb = new StringBuilder(text.length() + 32);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '{') {
                int close = text.indexOf('}', i);
                if (close > i + 1 && close - i <= 3) {
                    String index = text.substring(i + 1, close);
                    if (index.chars().allMatch(Character::isDigit)) {
                        int n = Integer.parseInt(index);
                        sb.append(n < args.length ? String.valueOf(args[n]) : text.substring(i, close + 1));
                        i = close + 1;
                        continue;
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}
