package com.halty.gramalyzerbot.analytics;

import java.util.Arrays;

/**
 * What was usual for a channel around the time of each post: the median of its neighbouring posts.
 * <p>
 * A channel that doubles its audience, a summer slump, or a Telegram-wide drop in reach (Telemetr measured views per
 * post falling by half across 1.7M channels in 2025–26) all move every post at once. Comparing a post with the
 * channel's all-time average mistakes that drift for "videos work" or "Tuesdays work"; comparing it with the posts
 * just before and after it doesn't.
 */
public final class Baseline {

    /** Neighbours on each side: 15 + 15 is about a month for a channel that posts daily, short enough to follow growth and long enough that one viral post barely moves it. */
    public static final int HALF = 15;

    private Baseline() {}

    /**
     * For each value (oldest first), the median of up to {@code 2 × half} neighbours, itself left out. Near either end
     * the window shifts inwards so that every post still has {@code 2 × half} neighbours when there are that many.
     */
    public static double[] medians(double[] values, int half) {
        int n = values.length;
        double[] out = new double[n];
        if (n == 0) return out;
        if (n == 1) { out[0] = values[0]; return out; }
        int want = Math.min(2 * half, n - 1);
        double[] buf = new double[want];
        for (int i = 0; i < n; i++) {
            int lo = i - half, hi = i + half;
            if (lo < 0) { hi -= lo; lo = 0; }
            if (hi > n - 1) { lo -= hi - (n - 1); hi = n - 1; }
            lo = Math.max(0, lo);
            int k = 0;
            for (int j = lo; j <= hi && k < want; j++) if (j != i) buf[k++] = values[j];
            out[i] = median(buf, k);
        }
        return out;
    }

    public static double[] medians(double[] values) { return medians(values, HALF); }

    /** Each value against its neighbours, as a ratio of {@code (value + 1) / (usual + 1)}; values oldest first. */
    public static double[] ratios(double[] values) {
        double[] usual = medians(values);
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (values[i] + 1) / (usual[i] + 1);
        return out;
    }

    static double median(double[] buf, int k) {
        double[] s = Arrays.copyOf(buf, k);
        Arrays.sort(s);
        return k % 2 == 1 ? s[k / 2] : (s[k / 2 - 1] + s[k / 2]) / 2;
    }
}
