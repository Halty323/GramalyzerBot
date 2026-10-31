package com.halty.gramalyzerbot.analytics;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

/**
 * Whether the {@link PostModel} actually predicts this channel: fit it on older posts, then see if it ranks the newest
 * ones in the right order. A forecast nobody has checked is a guess with a number on it, so the draft score only shows
 * a score once this passes.
 */
public final class Backtest {

    /**
     * @param status untested (too few posts to check), proven (newer posts came out in the predicted order more than
     *               chance would explain) or unproven
     * @param rho    Spearman rank correlation between predicted and actual results of the held-out posts
     * @param tested how many of the newest posts were held out
     */
    public record Check(String status, Double rho, int tested) {
        public boolean proven() { return "proven".equals(status); }

        public static Check untested() { return new Check("untested", null, 0); }
    }

    static final int MIN_TRAIN = 20, MIN_TEST = 8, MAX_TEST = 60;
    /** One-sided 90%: rho·√(n−1) is about standard normal when there is no relation. */
    static final double Z = 1.28;

    private Backtest() {}

    /** @param use posts oldest first; @param v their log results against their neighbours */
    public static Check run(List<Sample> use, double[] v, ZoneId zone) {
        int n = use.size();
        int test = Math.max(MIN_TEST, Math.min(MAX_TEST, n / 4));
        int train = n - test;
        if (train < MIN_TRAIN) return Check.untested();
        PostModel m = PostModel.fit(use.subList(0, train), Arrays.copyOf(v, train), zone);
        double[] pred = new double[test], actual = new double[test];
        for (int i = 0; i < test; i++) {
            pred[i] = m.predict(PostModel.features(use.get(train + i), zone));
            actual[i] = v[train + i];
        }
        double rho = spearman(pred, actual);
        if (Double.isNaN(rho)) return new Check("unproven", 0.0, test);
        boolean proven = rho > 0 && rho * Math.sqrt(test - 1) >= Z;
        return new Check(proven ? "proven" : "unproven", Math.round(rho * 100) / 100.0, test);
    }

    static double spearman(double[] a, double[] b) {
        return pearson(ranks(a), ranks(b));
    }

    /** Average ranks, so ties (posts with identical traits get identical predictions) share a rank. */
    static double[] ranks(double[] x) {
        int n = x.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (i, j) -> Double.compare(x[i], x[j]));
        double[] r = new double[n];
        for (int i = 0; i < n; ) {
            int j = i;
            while (j + 1 < n && x[order[j + 1]] == x[order[i]]) j++;
            double avg = (i + j) / 2.0;
            for (int k = i; k <= j; k++) r[order[k]] = avg;
            i = j + 1;
        }
        return r;
    }

    static double pearson(double[] a, double[] b) {
        int n = a.length;
        double ma = Arrays.stream(a).average().orElse(0), mb = Arrays.stream(b).average().orElse(0);
        double sab = 0, saa = 0, sbb = 0;
        for (int i = 0; i < n; i++) {
            sab += (a[i] - ma) * (b[i] - mb);
            saa += (a[i] - ma) * (a[i] - ma);
            sbb += (b[i] - mb) * (b[i] - mb);
        }
        return saa == 0 || sbb == 0 ? Double.NaN : sab / Math.sqrt(saa * sbb);
    }
}
