package com.halty.gramalyzerbot.seo;

import com.halty.gramalyzerbot.analytics.TextFeatures;

import java.util.*;
import java.util.regex.Pattern;

/**
 * How findable a channel is in Telegram's search. Telegram matches what people type against a channel's title and
 * username, so those carry most of the score; the description is what convinces someone in the results to open it.
 * <p>
 * Nobody outside Telegram knows what people search for, so the words a channel should be found by are taken from its
 * own posts: the words and hashtags it keeps coming back to.
 */
public final class SearchCheck {

    public enum Status { OK, WARN, BAD }

    /** @param id a {@code seo.check.<id>} translation key; @param words words the advice is about, if any */
    public record Check(String id, Status status, int points, int max, List<String> words) {}

    /** @param inTitle whether the title (or username) already has it; @param posts how many posts use it */
    public record Keyword(String word, int posts, boolean inTitle, boolean inDescription) {}

    /** @param grade good (80+), fair (50+) or poor */
    public record Result(int score, String grade, List<Check> checks, List<Keyword> keywords, List<String> titleIdeas,
                         List<String> usernameIdeas, String title, String username, String description, int postsRead) {}

    static final int TITLE_SHOWN = 32;
    static final int DESCRIPTION_MAX = 255;
    static final int MIN_POSTS = 6;
    private static final int KEYWORDS = 8;
    private static final Pattern LATIN = Pattern.compile("[a-z][a-z0-9]{2,}");
    private static final Pattern SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");

    private SearchCheck() {}

    public static Result check(String title, String username, String description, List<String> posts) {
        title = title == null ? "" : title.strip();
        username = username == null || username.isBlank() ? null : username.strip().replaceFirst("^@", "");
        description = description == null ? "" : description.strip();
        List<Keyword> keywords = keywords(posts, title, username, description);
        List<String> words = keywords.stream().map(Keyword::word).toList();
        List<Check> checks = new ArrayList<>();

        checks.add(username == null ? new Check("public", Status.BAD, 0, 15, List.of()) : new Check("public", Status.OK, 15, 15, List.of()));

        int length = title.codePointCount(0, title.length());
        checks.add(length >= 3 && length <= TITLE_SHOWN ? new Check("title_length", Status.OK, 10, 10, List.of())
                : length > TITLE_SHOWN && length <= 64 ? new Check("title_length", Status.WARN, 5, 10, List.of())
                : new Check("title_length", Status.BAD, 0, 10, List.of()));

        if (posts.size() < MIN_POSTS || keywords.isEmpty()) {
            checks.add(new Check("title_keyword_unknown", Status.WARN, 12, 25, List.of()));
        } else {
            List<String> top = words.subList(0, Math.min(3, words.size()));
            String haystack = title + " " + (username == null ? "" : username);
            if (top.stream().anyMatch(w -> has(haystack, w))) checks.add(new Check("title_keyword", Status.OK, 25, 25, List.of()));
            else if (words.stream().anyMatch(w -> has(haystack, w))) checks.add(new Check("title_keyword", Status.WARN, 15, 25, top));
            else checks.add(new Check("title_keyword", Status.BAD, 0, 25, top));
        }

        checks.add(decorated(title) ? new Check("title_clean", Status.WARN, 4, 10, List.of()) : new Check("title_clean", Status.OK, 10, 10, List.of()));

        if (username == null) {
            checks.add(new Check("username_readable", Status.BAD, 0, 10, List.of()));
            checks.add(new Check("username_match", Status.BAD, 0, 10, List.of()));
        } else {
            boolean readable = username.length() <= 20 && username.chars().filter(c -> c == '_').count() <= 1 && !username.matches(".*\\d{2,}$");
            checks.add(readable ? new Check("username_readable", Status.OK, 10, 10, List.of()) : new Check("username_readable", Status.WARN, 5, 10, List.of()));
            String lower = username.toLowerCase(Locale.ROOT);
            boolean match = latinWords(title).stream().anyMatch(lower::contains)
                    || words.stream().filter(w -> LATIN.matcher(w).matches()).anyMatch(w -> lower.contains(TextFeatures.stem(w)));
            checks.add(match ? new Check("username_match", Status.OK, 10, 10, List.of()) : new Check("username_match", Status.WARN, 5, 10, List.of()));
        }

        int d = description.codePointCount(0, description.length());
        checks.add(d >= 50 ? new Check("description_present", Status.OK, 10, 10, List.of())
                : d > 0 ? new Check("description_present", Status.WARN, 5, 10, List.of())
                : new Check("description_present", Status.BAD, 0, 10, List.of()));

        if (!keywords.isEmpty()) {
            String desc = description;
            long found = words.stream().filter(w -> has(desc, w)).count();
            List<String> missing = words.stream().filter(w -> !has(desc, w)).limit(3).toList();
            checks.add(found >= 2 ? new Check("description_keyword", Status.OK, 10, 10, List.of())
                    : found == 1 ? new Check("description_keyword", Status.WARN, 5, 10, missing)
                    : new Check("description_keyword", Status.BAD, 0, 10, missing));
        } else {
            checks.add(new Check("description_keyword_unknown", Status.WARN, 5, 10, List.of()));
        }

        int score = checks.stream().mapToInt(Check::points).sum();
        String grade = score >= 80 ? "good" : score >= 50 ? "fair" : "poor";
        return new Result(score, grade, checks, keywords, titleIdeas(title, keywords, checks), usernameIdeas(title, username, keywords),
                title, username, description, posts.size());
    }

    // ------------------------------------------------------------------ keywords

    /**
     * Words any channel uses, whatever it's about: nobody finds a channel by searching for them. Short function words
     * are already dropped by {@link TextFeatures#keywords}.
     */
    private static final Set<String> GENERIC = Set.copyOf(Arrays.asList(String.join(" ",
            "take make like know good best need want time year years week weeks days people thing things first last next new more read post posts channel comment comments share follow subscribe link here look today tomorrow yesterday thanks thank please great little list part come going made makes takes",
            "worth full long short down back save single notice links three four five seven eight nine every same right left high small open free easy hard real sure keep give find show tell work works working start started stop using used update note notes ways step steps around better maybe actually enough something anything nothing everything someone others again later early still almost quite whole half kind sort able done gets getting goes seen says said asked think feel looks",
            "канал канала каналы пост поста посты постов подписывайтесь подпишитесь ссылка ссылке сегодня завтра вчера спасибо пожалуйста просто тоже очень новый новые лучший лучшие первый можно нужно читать комментарии",
            "канал каналу пости посту підписуйтесь посилання сьогодні завтра вчора дякую будь ласка просто також дуже новий нові кращий перший можна треба читати коментарі",
            "canal post posts hoje amanhã obrigado melhor novo nova primeiro mais comentários link siga leia mañana gracias mejor nuevo nueva primero comentarios sigue",
            "kanal hari besok terima kasih baru terbaik pertama komentar ikuti bugün yarın teşekkürler yeni ilk yorum takip").split(" ")));

    /** A word in more of the posts than this is a signature, not a topic; a channel's main word can be in most of them. */
    private static final double SIGNATURE = 0.85;

    /** Words and hashtags used in at least two posts (and three for words), but not in nearly all of them (a signature). */
    static List<Keyword> keywords(List<String> posts, String title, String username, String description) {
        Map<String, Integer> df = new HashMap<>();
        Map<String, Map<String, Integer>> forms = new HashMap<>();
        Map<String, Integer> tags = new HashMap<>();
        for (String p : posts) {
            Set<String> seen = new HashSet<>();
            for (String w : TextFeatures.keywords(p)) {
                String stem = TextFeatures.stem(w);
                forms.computeIfAbsent(stem, k -> new HashMap<>()).merge(w, 1, Integer::sum);
                if (seen.add(stem)) df.merge(stem, 1, Integer::sum);
            }
            for (String t : TextFeatures.hashtags(p)) tags.merge(t, 1, Integer::sum);
        }
        int n = posts.size();
        List<Keyword> out = new ArrayList<>();
        String haystack = title + " " + (username == null ? "" : username);
        df.forEach((stem, count) -> {
            if (count < 3 || count > Math.max(3, n * SIGNATURE)) return;
            String word = forms.get(stem).entrySet().stream()
                    .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder()))).orElseThrow().getKey();
            if (forms.get(stem).keySet().stream().anyMatch(GENERIC::contains)) return;
            out.add(new Keyword(word, count, has(haystack, word), has(description, word)));
        });
        tags.forEach((tag, count) -> {
            if (count < 2 || count > Math.max(2, n * SIGNATURE) || df.containsKey(TextFeatures.stem(tag))) return;
            out.add(new Keyword(tag, count, has(haystack, tag), has(description, tag)));
        });
        out.sort(Comparator.comparingInt(Keyword::posts).reversed().thenComparing(Keyword::word));
        return out.subList(0, Math.min(KEYWORDS, out.size()));
    }

    /** Whether the text has the word, in any inflected form (same six-letter stem) or glued into a username. */
    static boolean has(String text, String word) {
        if (text == null || text.isBlank()) return false;
        String stem = TextFeatures.stem(word);
        String lower = text.toLowerCase(Locale.ROOT);
        for (String tok : SPLIT.split(lower)) if (!tok.isEmpty() && TextFeatures.stem(tok).equals(stem)) return true;
        return stem.length() >= 5 && lower.replace("_", "").contains(stem);
    }

    // ------------------------------------------------------------------ title

    /** More than two emoji, shouting in capitals, or decorative symbols nobody types into a search box. */
    static boolean decorated(String title) {
        int emoji = 0, letters = 0, upper = 0, symbols = 0;
        for (int cp : title.codePoints().toArray()) {
            int type = Character.getType(cp);
            if (Character.isLetter(cp)) {
                letters++;
                if (Character.isUpperCase(cp)) upper++;
            } else if (cp >= 0x1F000 || cp >= 0x2600 && cp <= 0x27BF) {
                emoji++;
            } else if (type == Character.OTHER_SYMBOL || type == Character.MATH_SYMBOL && cp > 0x7F) {
                symbols++;
            }
        }
        return emoji > 2 || symbols > 1 || letters >= 6 && upper > letters * 0.6;
    }

    private static List<String> latinWords(String title) {
        List<String> out = new ArrayList<>();
        for (String tok : SPLIT.split(title.toLowerCase(Locale.ROOT))) if (tok.length() >= 4 && LATIN.matcher(tok).matches()) out.add(tok);
        return out;
    }

    /** The title with the channel's main word added, when it doesn't have one yet. */
    static List<String> titleIdeas(String title, List<Keyword> keywords, List<Check> checks) {
        boolean needs = checks.stream().anyMatch(c -> c.id().equals("title_keyword") && c.status() != Status.OK);
        if (!needs || title.isBlank()) return List.of();
        String clean = title.replaceAll("[\\p{So}\\p{Cn}\\x{1F000}-\\x{1FFFF}]", "").replaceAll("\\s+", " ").strip();
        if (clean.isEmpty()) clean = title;
        List<String> out = new ArrayList<>();
        for (Keyword k : keywords.subList(0, Math.min(2, keywords.size()))) {
            String word = capital(k.word());
            for (String idea : List.of(clean + " · " + word, word + ": " + clean)) {
                if (idea.codePointCount(0, idea.length()) <= 40 && !out.contains(idea)) out.add(idea);
            }
            if (out.size() >= 3) break;
        }
        return out.subList(0, Math.min(3, out.size()));
    }

    /** Usernames made of the title's and the main words' Latin parts. Telegram decides whether they're free. */
    static List<String> usernameIdeas(String title, String username, List<Keyword> keywords) {
        List<String> parts = new ArrayList<>(latinWords(title));
        List<String> latinKeywords = keywords.stream().map(Keyword::word).filter(w -> LATIN.matcher(w).matches()).limit(3).toList();
        boolean matches = username != null && (parts.stream().anyMatch(username.toLowerCase(Locale.ROOT)::contains)
                || latinKeywords.stream().anyMatch(w -> username.toLowerCase(Locale.ROOT).contains(TextFeatures.stem(w))));
        if (matches && username.length() <= 20) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (String k : latinKeywords) {
            for (String p : parts) {
                if (p.equals(k)) continue;
                out.add(p + k);
                out.add(p + "_" + k);
                out.add(k + "_" + p);
            }
            if (parts.isEmpty()) out.add(k + "_channel");
        }
        if (latinKeywords.isEmpty() && parts.size() >= 2) out.add(parts.get(0) + "_" + parts.get(1));
        return out.stream().filter(u -> u.length() >= 5 && u.length() <= 24 && !u.equalsIgnoreCase(username)).limit(3).toList();
    }

    /** "printing" → "Printing"; short Latin words are usually abbreviations: "ai" → "AI". */
    private static String capital(String w) {
        if (w.length() <= 3 && LATIN.matcher(w).matches()) return w.toUpperCase(Locale.ROOT);
        return w.isEmpty() || w.startsWith("#") ? w : w.substring(0, w.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT) + w.substring(w.offsetByCodePoints(0, 1));
    }
}
