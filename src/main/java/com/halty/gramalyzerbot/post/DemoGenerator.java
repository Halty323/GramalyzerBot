package com.halty.gramalyzerbot.post;

import com.halty.gramalyzerbot.analytics.PostKind;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Builds a believable fake channel so people can explore the bot before connecting a real one.
 * The data has planted patterns (Tue/Thu evenings, video and each theme's first topic, such as #ai, do best) – the
 * engine's tests rely on that.
 */
public final class DemoGenerator {

    public record DemoPost(Instant postedAt, PostKind kind, String text, boolean hasLink, int reactions,
                           int comments, String breakdown) {}

    private static final double[] HOUR_WEIGHT = {1, .5, .3, .2, .2, .5, 1, 3, 4, 6, 4, 4, 5, 5, 4, 4, 4, 5, 7, 7, 6, 5, 3, 2};
    private static final double[] DAY_MULT = {1.0, 1.4, 1.0, 1.3, 1.0, 0.8, 0.7};
    private static final PostKind[] KINDS = {PostKind.TEXT, PostKind.PHOTO, PostKind.VIDEO, PostKind.POLL, PostKind.ALBUM, PostKind.ANIMATION};
    private static final double[] KIND_WEIGHT = {.35, .25, .15, .05, .1, .1};
    private static final double[] KIND_MULT = {1.0, 1.2, 1.6, 1.3, 1.1, 0.9};
    private static final double[] TOPIC_WEIGHT = {.25, .2, .25, .15, .15};
    private static final double[] TOPIC_MULT = {1.7, 1.3, 1.0, 0.6, 1.1};

    /**
     * What a demo channel is about: five topics (the first does best, the fourth worst), a few headlines and
     * follow-up sentences for each. Every theme shares the same timing, format and topic weights.
     */
    public enum Theme {
        TECH(new String[]{"ai", "tutorials", "news", "memes", "reviews"}, new String[][]{
                {"New model released and the benchmarks look wild", "How a transformer actually predicts the next token",
                        "Open weights are catching up with the big labs", "We tested the latest model on real tasks"},
                {"Step by step guide to building your first bot", "A practical guide to caching without pain",
                        "Build a small project in one evening, step by step", "Beginner guide: set up your toolchain properly"},
                {"Breaking: company announces quarterly results", "Industry weekly roundup is out",
                        "Regulators publish the long awaited framework", "Funding round closes for a local startup"},
                {"Monday mood again lol", "When the deploy works on the first try",
                        "Me pretending to understand the meeting", "Friday afternoon energy"},
                {"Honest review after two weeks of daily use", "Is the premium plan really worth the price",
                        "We compared three popular tools side by side", "Unboxing and first impressions"}}, new String[][]{
                {"We ran it on our own set of real tasks, and the full results table is pinned in the comments.",
                        "The short version: faster, cheaper and a lot better at following long instructions than last time.",
                        "The weights are public under a permissive licence, so you can try it on your own hardware today."},
                {"Every step comes with a code sample you can copy, and the finished project is linked at the end.",
                        "Bookmark this one: it takes about an hour and all you need is a laptop and a free account.",
                        "Stuck on a step? Ask in the comments and we will help you get it running."},
                {"Here is what it means for you, and what is likely to change over the next few months.",
                        "We will keep this post updated as more details come out during the day.",
                        "Three things worth knowing, in plain words and without the hype."},
                {"Tag the colleague who needs to see this.", "Every developer has been here at least once.",
                        "Send this to your team chat, no context needed."},
                {"We paid for it ourselves and nobody sponsored this review, so here is the unfiltered take.",
                        "Pros, cons and who should skip it are in the full write-up below.",
                        "Our verdict after daily use: worth it for most people, with one big caveat."}}),
        DESIGN(new String[]{"typography", "branding", "inspiration", "humor", "ux"}, new String[][]{
                {"Five typefaces we keep coming back to this year", "Why letter spacing matters more than the font you pick",
                        "A free variable font that handles every weight beautifully", "How to pair a serif and a sans without a fight"},
                {"Rebrand teardown: what the new logo gets right", "The best identities start with a single word",
                        "Colour systems from three studios we admire", "Behind a packaging redesign that stands out on the shelf"},
                {"Moodboard of the week: soft gradients and paper textures", "Posters from the Swiss archive that still feel modern",
                        "A studio portfolio that rewards every scroll", "Colour palettes pulled from old film stills"},
                {"When the client asks to make the logo bigger again", "Me choosing between two identical shades of grey",
                        "Kerning gone wrong: this week's collection", "Friday feedback round energy"},
                {"Small interface details that make an app feel calm", "Empty states worth stealing for your next product",
                        "A checkout flow that drops three steps without losing trust", "Dark mode done properly: contrast, depth and colour"}},
                new String[][]{
                {"Look at the lowercase a and g: at small sizes they carry most of the personality in body text.",
                        "All of them are free for commercial use, and the download links are in the comments.",
                        "Set the body at 16 to 18 pixels with a line height around 1.5 and most of these read beautifully."},
                {"The strongest part is restraint: one colour, one typeface and a mark that still works as a favicon.",
                        "Notice how the tone of voice carries over from the logo to the product copy and even the error pages.",
                        "We asked the studio about their process: the first two weeks went into research and naming alone."},
                {"Save this for the next time you are stuck at the blank canvas stage.",
                        "Every piece links back to the original artist, so go and show them some love.",
                        "Notice the generous margins and how a single accent colour holds the whole layout together."},
                {"Tag the designer who needs to see this today.", "Send this to your project manager, no context needed.",
                        "We have all been there, and honestly some of us are still there."},
                {"Every screen has one primary action, and everything else steps back with a lighter weight and colour.",
                        "Save these for your next project and tell us which one you would ship first.",
                        "The trick is motion: a 200 millisecond transition makes the change readable instead of jarring."}}),
        MAKERS(new String[]{"woodworking", "electronics", "printing", "fails", "tools"}, new String[][]{
                {"Building a walnut side table with only hand tools", "The joint that changed how I build drawers",
                        "A beginner workbench you can finish in a weekend", "Finishing oils compared on oak, ash and pine"},
                {"A weather station built around a five euro board", "Our community made a tiny synth from scrap parts",
                        "Reading a datasheet without getting lost", "A soldering station upgrade for under fifty euros"},
                {"Print settings that finally fixed my stringing", "Ten functional prints that earn their spool",
                        "Designing snap fits that do not snap off", "PLA, PETG or ABS: which one for outdoor parts"},
                {"Glued it to the bench again", "When the print lets go at 98 percent",
                        "Measure twice, cut once, they said", "Workshop cleanup day, before and after"},
                {"Honest review of a budget cordless drill after a year", "Five measuring tools every workshop should have",
                        "How to sharpen chisels in ten minutes", "Workshop tour: a full shop in one garage bay"}}, new String[][]{
                {"The cut list, timings and the mistakes I made are all in the thread, so you can skip them on your build.",
                        "Take your time with the layout lines: a sharp marking knife does more for accuracy than any jig.",
                        "Total cost was under forty euros in timber, plus a lot of patience and two evenings of sanding."},
                {"The full parts list and wiring diagram are pinned in the comments, with links to cheap suppliers.",
                        "Double check the polarity before you power it on: the magic smoke only comes out once.",
                        "The code is open and runs on any board with wifi, so use whatever is already in your drawer."},
                {"The files are free to download, and the settings above work on most printers out of the box.",
                        "Dry your filament first: half of the problems people send us come down to moisture.",
                        "Print it in PETG if it will live in the sun, and add one more wall if it has to take a load."},
                {"Share your own workshop disasters below and we will feature the best ones on Friday.",
                        "Lesson learned, and the project is back on the bench for round two.",
                        "Everyone who makes things has a pile like this, so do not let it stop you."},
                {"No sponsorship here: we bought it ourselves and used it on real projects before writing a word.",
                        "Show us your setup in the comments, and the best workshop gets featured next week.",
                        "Buy the cheap one first, and only upgrade the tools you actually wear out."}});

        final String[] topics;
        final String[][] headlines;
        final String[][] details;

        Theme(String[] topics, String[][] headlines, String[][] details) {
            this.topics = topics;
            this.headlines = headlines;
            this.details = details;
        }

        /** Picks the theme whose subject a channel's name or description mentions; tech otherwise. */
        public static Theme guess(String... hints) {
            String all = String.join(" ", Arrays.stream(hints).filter(Objects::nonNull).toList()).toLowerCase(Locale.ROOT);
            if (all.contains("design")) return DESIGN;
            if (all.contains("maker") || all.contains("make things")) return MAKERS;
            return TECH;
        }
    }

    private DemoGenerator() {}

    public static List<DemoPost> generate(Instant now, ZoneId zone, long seed, int days) {
        return generate(now, zone, seed, days, Theme.TECH);
    }

    public static List<DemoPost> generate(Instant now, ZoneId zone, long seed, int days, Theme theme) {
        Random rng = new Random(seed);
        // Wording comes from its own stream, so a theme changes what the posts say but not when or how well they do.
        Random words = new Random(~seed);
        List<DemoPost> out = new ArrayList<>();
        LocalDate today = now.atZone(zone).toLocalDate();
        for (int back = days - 1; back >= 0; back--) {
            LocalDate date = today.minusDays(back);
            int count = rng.nextDouble() < 0.12 ? 0 : 1 + (rng.nextDouble() < 0.3 ? 1 : 0) + (rng.nextDouble() < 0.08 ? 1 : 0);
            for (int c = 0; c < count; c++) {
                int hour = weighted(rng, HOUR_WEIGHT);
                Instant at = date.atTime(hour, rng.nextInt(60), rng.nextInt(60)).atZone(zone).toInstant();
                if (at.isAfter(now)) continue;
                int ti = weighted(rng, TOPIC_WEIGHT), ki = weighted(rng, KIND_WEIGHT);
                String[] heads = theme.headlines[ti], details = theme.details[ti];
                StringBuilder text = new StringBuilder(heads[rng.nextInt(heads.length)]);
                int extra = rng.nextInt(4);
                int first = words.nextInt(details.length);
                for (int e = 0; e < Math.min(extra, details.length); e++)
                    text.append(e == 0 ? "\n\n" : " ").append(details[(first + e) % details.length]);
                boolean link = rng.nextDouble() < 0.25;
                if (link) text.append("\n\nhttps://example.com/post").append(rng.nextInt(1000));
                text.append("\n\n#").append(theme.topics[ti]);

                DayOfWeek dow = date.getDayOfWeek();
                double mult = DAY_MULT[dow.getValue() - 1] * hourMult(hour) * TOPIC_MULT[ti] * KIND_MULT[ki];
                int reactions = (int) Math.round(20 * mult * Math.exp(rng.nextGaussian() * 0.5));
                int comments = (int) Math.round(reactions * 0.12 * Math.exp(rng.nextGaussian() * 0.4));
                int fire = (int) Math.round(reactions * 0.4), heart = (int) Math.round(reactions * 0.2);
                String breakdown = "🔥:" + fire + ",👍:" + (reactions - fire - heart) + ",❤:" + heart;
                out.add(new DemoPost(at, KINDS[ki], text.toString(), link, reactions, comments, breakdown));
            }
        }
        return out;
    }

    private static double hourMult(int h) {
        if (h == 18 || h == 19) return 1.8;
        if (h == 17 || h == 20) return 1.3;
        if (h == 9) return 1.4;
        if (h == 8 || h == 10) return 1.15;
        if (h <= 5) return 0.6;
        return 1.0;
    }

    private static int weighted(Random rng, double[] w) {
        double total = 0;
        for (double x : w) total += x;
        double r = rng.nextDouble() * total;
        for (int i = 0; i < w.length; i++) {
            r -= w[i];
            if (r <= 0) return i;
        }
        return w.length - 1;
    }
}
