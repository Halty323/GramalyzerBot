package com.halty.gramalyzerbot.analytics;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * What "doing well" means: reactions and (when a discussion group is linked) comments, or views. The Bot API does not
 * expose view counts, so views are only known for public channels, read from their web preview.
 * <p>
 * Views are what readers, Telegram's own statistics and advertisers count, so {@link #AUTO} (the default) uses them
 * whenever enough of the channel's recent posts have a 24-hour reading, and falls back to reactions and comments.
 * A comment counts three reactions because it takes far more effort than a tap.
 */
public enum Metric {
    AUTO("Automatic", "views after 24 hours when known, otherwise reactions + 3 × comments"),
    ENGAGEMENT("Balanced", "reactions + 3 × comments"),
    REACTIONS("Reactions", "reactions only"),
    COMMENTS("Comments", "comments only"),
    VIEWS("Views", "views after 24 hours");

    /** {@link #AUTO} picks views when at least this many of the last {@value #RECENT} settled posts have them… */
    static final int MIN_VIEWS = 20;
    static final int RECENT = 30;

    private final String label;
    private final String formula;

    Metric(String label, String formula) {
        this.label = label;
        this.formula = formula;
    }

    public String label() { return label; }

    public String formula() { return formula; }

    public double score(int reactions, int comments, boolean commentsKnown) {
        int c = commentsKnown ? comments : 0;
        return switch (this) {
            case ENGAGEMENT, AUTO -> reactions + 3.0 * c;
            case REACTIONS -> reactions;
            case COMMENTS -> c;
            case VIEWS -> 0;
        };
    }

    /** The score of one post; with {@link #VIEWS}, only posts whose day-one views are known count. */
    public double score(Sample s) {
        return this == VIEWS ? (s.views() == null ? 0 : s.views()) : score(s.reactions(), s.comments(), s.commentsKnown());
    }

    /** Whether a post can be scored at all: views are unknown for private channels and posts read before tracking began. */
    public boolean scores(Sample s) { return this != VIEWS || s.views() != null; }

    /** The metric actually used for these posts: {@link #AUTO} becomes views or engagement, anything else stays. */
    public Metric resolve(List<Sample> samples, Instant now) {
        if (this != AUTO) return this;
        Instant settled = now.minus(Duration.ofHours(24));
        List<Sample> recent = samples.stream().filter(s -> !s.excluded() && s.postedAt().isBefore(settled))
                .sorted(Comparator.comparing(Sample::postedAt).reversed()).limit(RECENT).toList();
        long withViews = recent.stream().filter(s -> s.views() != null).count();
        return withViews >= Math.min(MIN_VIEWS, RECENT) && withViews >= recent.size() * 0.7 ? VIEWS : ENGAGEMENT;
    }
}
