package com.halty.gramalyzerbot.i18n;

import com.halty.gramalyzerbot.user.User;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class I18nTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d}");
    private static final Pattern TAG = Pattern.compile("</?(b|i|u|s|code|pre|a)\\b[^>]*>");
    private static final Pattern PLURAL = Pattern.compile("^(.+)\\.(zero|one|two|few|many|other)$");

    private static Properties bundle(Lang l) throws IOException {
        Properties p = new Properties();
        try (InputStream in = I18nTest.class.getResourceAsStream("/i18n/bot_" + l.code() + ".properties")) {
            assertNotNull(in, "missing bundle for " + l);
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    private static List<String> all(Pattern p, String s) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(s);
        while (m.find()) out.add(m.group().replaceAll("\\s.*>", ">"));
        Collections.sort(out);
        return out;
    }

    /** Every language covers every English text, with the same placeholders and the same Telegram HTML. */
    @Test
    void bundlesMatchEnglish() throws IOException {
        Properties en = bundle(Lang.EN);
        Set<String> plurals = new TreeSet<>();
        for (String k : en.stringPropertyNames()) {
            Matcher m = PLURAL.matcher(k);
            if (m.matches()) plurals.add(m.group(1));
        }
        List<String> problems = new ArrayList<>();
        for (Lang l : Lang.values()) {
            if (l == Lang.EN) continue;
            Properties tr = bundle(l);
            for (String k : en.stringPropertyNames()) {
                Matcher m = PLURAL.matcher(k);
                if (m.matches() && plurals.contains(m.group(1))) continue;
                String theirs = tr.getProperty(k);
                if (theirs == null) {
                    problems.add(l + " lacks " + k);
                    continue;
                }
                String ours = en.getProperty(k);
                if (!new TreeSet<>(all(PLACEHOLDER, ours)).equals(new TreeSet<>(all(PLACEHOLDER, theirs))))
                    problems.add(l + " " + k + " placeholders differ: " + theirs);
                if (!all(TAG, ours).equals(all(TAG, theirs))) problems.add(l + " " + k + " HTML differs: " + theirs);
            }
            for (String base : plurals) {
                if (Plurals.category(l, 5).equals("other") && !tr.containsKey(base + ".other")) problems.add(l + " lacks " + base + ".other");
                for (long n : new long[]{0, 1, 2, 3, 5, 11, 21, 100}) {
                    String form = base + "." + Plurals.category(l, n);
                    if (!tr.containsKey(form) && !tr.containsKey(base + ".other")) problems.add(l + " lacks " + form);
                }
            }
            for (String k : tr.stringPropertyNames()) {
                Matcher m = PLURAL.matcher(k);
                boolean known = en.containsKey(k) || (m.matches() && plurals.contains(m.group(1)));
                if (!known) problems.add(l + " has an unknown key " + k);
                if (tr.getProperty(k).contains("''")) problems.add(l + " " + k + " has a doubled apostrophe");
                if (m.matches() && plurals.contains(m.group(1))) {
                    Set<String> allowed = new TreeSet<>(all(PLACEHOLDER, en.getProperty(m.group(1) + ".other")));
                    if (!allowed.containsAll(all(PLACEHOLDER, tr.getProperty(k)))) problems.add(l + " " + k + " has a stray placeholder");
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void pluralRules() {
        assertEquals("one", Plurals.category(Lang.RU, 1));
        assertEquals("few", Plurals.category(Lang.RU, 3));
        assertEquals("many", Plurals.category(Lang.RU, 5));
        assertEquals("many", Plurals.category(Lang.RU, 11));
        assertEquals("many", Plurals.category(Lang.UK, 14));
        assertEquals("one", Plurals.category(Lang.UK, 21));
        assertEquals("few", Plurals.category(Lang.UK, 22));
        assertEquals("zero", Plurals.category(Lang.AR, 0));
        assertEquals("two", Plurals.category(Lang.AR, 2));
        assertEquals("few", Plurals.category(Lang.AR, 105));
        assertEquals("many", Plurals.category(Lang.AR, 11));
        assertEquals("other", Plurals.category(Lang.AR, 100));
        assertEquals("one", Plurals.category(Lang.HI, 0));
        assertEquals("other", Plurals.category(Lang.ID, 1));
        assertEquals("one", Plurals.category(Lang.EN, 1));
        assertEquals("other", Plurals.category(Lang.EN, 0));
    }

    @Test
    void countedPhrases() {
        assertEquals("1 post", Tr.in(Lang.EN, () -> Tr.n("n.posts", 1)));
        assertEquals("1,200 posts", Tr.in(Lang.EN, () -> Tr.n("n.posts", 1200)));
        String ru = Tr.in(Lang.RU, () -> Tr.n("n.posts", 1) + "|" + Tr.n("n.posts", 3) + "|" + Tr.n("n.posts", 5));
        String[] forms = ru.split("\\|");
        assertEquals(3, new HashSet<>(List.of(forms)).size(), ru);
        assertTrue(forms[0].startsWith("1 "), ru);
    }

    @Test
    void formatting() {
        assertEquals("a 1 b x {2}", Tr.format("a {0} b {1} {2}", 1, "x"));
        assertEquals("I'm {name}", Tr.format("I'm {name}", 1));
        assertEquals("no args {0}", Tr.format("no args {0}"));
    }

    @Test
    void missingKeysFallBack() {
        assertEquals("no.such.key", Tr.in(Lang.UK, () -> Tr.t("no.such.key")));
        assertEquals(Tr.in(Lang.EN, () -> Tr.t("btn.back")).isBlank(), false);
        assertTrue(Tr.in(Lang.RU, () -> Tr.has("btn.back")));
    }

    @Test
    void languagePicking() {
        assertEquals(Optional.of(Lang.PT), Lang.fromCode("pt-br"));
        assertEquals(Optional.of(Lang.UK), Lang.fromCode("uk"));
        assertEquals(Optional.of(Lang.RU), Lang.fromCode("be"));
        assertEquals(Optional.of(Lang.ID), Lang.fromCode("ms"));
        assertEquals(Optional.empty(), Lang.fromCode("de"));
        assertEquals(Optional.empty(), Lang.fromCode(null));

        User u = new User();
        assertEquals(Lang.EN, Lang.of(u));
        u.setLanguageCode("fa");
        assertEquals(Lang.FA, Lang.of(u));
        u.setLanguage("tr");
        assertEquals(Lang.TR, Lang.of(u), "a chosen language wins over the app's");
        assertEquals(Lang.EN, Lang.of(null));
    }

    @Test
    void threadLanguageIsRestored() {
        Tr.use(Lang.ES);
        assertEquals("ok", Tr.in(Lang.VI, () -> {
            assertEquals(Lang.VI, Tr.lang());
            return "ok";
        }));
        assertEquals(Lang.ES, Tr.lang());
        Tr.use(null);
        assertEquals(Lang.EN, Tr.lang());
    }
}
