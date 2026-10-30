package com.halty.gramalyzerbot.analytics;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Language-agnostic text helpers: hashtags, links and statistically-derived keywords. */
public final class TextFeatures {

    private static final Pattern HASHTAG = Pattern.compile("(?<![\\p{L}\\p{N}_])#(\\p{L}[\\p{L}\\p{N}_]{1,63})");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.|t\\.me/)\\S+");
    private static final Pattern MENTION = Pattern.compile("@\\w+");
    private static final Pattern SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** Common function words (en/de/es/fr/ru) of 4+ letters; the engine also drops words that are in most posts. */
    private static final Set<String> STOP = Set.of(String.join(" ",
            "that this with from your have will what when where which their there they them then than been were would could should about into also just more most some such only over very much many does here each other after before because these those while still even both being through between again against during without within every another really never always today",
            "nicht eine einen einem einer dass auch oder aber wird werden wurde sind sein sich noch nach wenn dann über mehr diese dieser dieses haben hatte kann können schon immer jetzt hier dort sehr gibt heute alle viel",
            "para pero como esta este esto estos estas porque cuando donde entre sobre también todo todos hasta desde puede tiene hacer ahora aquí había sido",
            "dans pour avec cette sont mais plus être avoir fait comme tout tous nous vous elle elles leur leurs aussi sans sous très donc alors ainsi",
            "только будет когда чтобы этого также между после перед очень можно нужно может который которые потому более этот была были есть свой себя всех всего даже").split(" "));

    /**
     * Marks of a paid post: ad hashtags in the common languages, Russia's "erid" ad token, or a post that opens with
     * "Реклама" / "Advertising". Sponsored posts usually reach fewer readers and say nothing about what the channel's
     * own audience likes, so the analysis leaves them out.
     */
    private static final Pattern AD = Pattern.compile(
            "(?iU)(?<![\\p{L}\\p{N}_])#(?:ad|ads|advert|advertising|sponsored|promo|partner|реклама|промо|партнер|партнёр|спонсор|anuncio|publicidad|publicidade|patrocinado|reklam|iklan|werbung|anzeige)(?![\\p{L}\\p{N}_])"
                    + "|\\berid\\s*[:=]?\\s*[\\p{L}\\p{N}]{6,}"
                    + "|^\\s*(?:реклама|advertising|sponsored)\\b");

    private TextFeatures() {}

    public static boolean isAd(String text) {
        return text != null && !text.isBlank() && AD.matcher(text).find();
    }

    /** Lower-cased hashtags without the leading '#', in order of appearance. */
    public static List<String> hashtags(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return List.of();
        Matcher m = HASHTAG.matcher(text);
        while (m.find()) out.add(m.group(1).toLowerCase(Locale.ROOT));
        return List.copyOf(out);
    }

    public static boolean hasLink(String text) {
        return text != null && URL.matcher(text).find();
    }

    /**
     * Distinct lower-cased words of 4–24 letters. URLs, @mentions and hashtags are removed. Stop-words
     * are not filtered here – the engine drops words that appear in too many posts, which works for
     * any language.
     */
    /**
     * A crude, language-independent stem: the first six letters of longer words. Inflected forms – "посылка",
     * "посылки", "посылками", or "update", "updates", "updated" – then count as one word, which matters for
     * languages like Russian, where the same word rarely repeats in exactly the same form.
     */
    public static String stem(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        return w.codePointCount(0, w.length()) <= 6 ? w : w.substring(0, w.offsetByCodePoints(0, 6));
    }

    public static Set<String> keywords(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return out;
        String clean = URL.matcher(text).replaceAll(" ");
        clean = MENTION.matcher(clean).replaceAll(" ");
        clean = HASHTAG.matcher(clean).replaceAll(" ");
        for (String tok : SPLIT.split(clean.toLowerCase(Locale.ROOT))) {
            int len = tok.codePointCount(0, tok.length());
            if (len < 4 || len > 24) continue;
            if (tok.chars().allMatch(Character::isDigit) || STOP.contains(tok)) continue;
            out.add(tok);
        }
        return out;
    }
}
