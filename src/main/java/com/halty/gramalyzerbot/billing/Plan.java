package com.halty.gramalyzerbot.billing;

/**
 * What a person pays for. Limits apply to the channels they <b>own</b> here (demo channels don't count) and to their
 * own watchlist; channels they only help moderate count against their owner's plan. Prices live in
 * {@link BillingProperties} so they can change without a release.
 */
public enum Plan {
    FREE(1, 3),
    CREATOR(3, 10),
    PRO(10, 25);

    private final int channels;
    private final int watchlist;

    Plan(int channels, int watchlist) {
        this.channels = channels;
        this.watchlist = watchlist;
    }

    /** How many of their own channels get insights, digests and nudges. */
    public int channels() { return channels; }

    /** How many public channels they can keep on their watchlist. */
    public int watchlist() { return watchlist; }

    public boolean paid() { return this != FREE; }
}
