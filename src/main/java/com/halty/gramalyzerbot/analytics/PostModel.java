package com.halty.gramalyzerbot.analytics;

import java.time.ZoneId;
import java.util.*;

/**
 * One model of what a post's traits do on a channel, all traits at once: format, length, link and time of day.
 * <p>
 * Looking at each trait on its own double-counts: when a channel's videos are also its short posts, "videos do well"
 * and "short posts do well" are one effect, not two, and multiplying them promises far too much. Here every trait is
 * fitted together (ridge regression on the post's log result against its neighbours, see {@link Baseline}), so each
 * gets only its own share. The ridge pulls small groups towards "typical", like the shrinkage in
 * {@link AnalysisEngine}, and each effect carries a 90% range.
 */
public final class PostModel {

    public enum Dim { FORMAT, LENGTH, LINK, TIME }

    /** @param lift × a typical post, all else equal; {@code low}–{@code high} is its 90% range */
    public record Effect(Dim dim, String key, int posts, double lift, double low, double high) {
        /** The range lies wholly above or below 1: the data says this trait matters, one way or the other. */
        public boolean clear() { return low > 1 || high < 1; }
    }

    static final double LAMBDA = 3;
    static final double Z90 = 1.645;
    public static final int BLOCK_HOURS = 4;

    private final List<String> columns;
    private final Map<String, Integer> index;
    private final int[] counts;
    private final double[] beta;
    private final double[][] cov;
    private final int posts;

    private PostModel(List<String> columns, int[] counts, double[] beta, double[][] cov, int posts) {
        this.columns = columns;
        this.counts = counts;
        this.beta = beta;
        this.cov = cov;
        this.posts = posts;
        this.index = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) index.put(columns.get(i), i);
    }

    /** The first hour of the {@value #BLOCK_HOURS}-hour block an hour falls in, e.g. 18 → "16". */
    public static String timeKey(int hour) { return String.valueOf(hour / BLOCK_HOURS * BLOCK_HOURS); }

    public static Map<Dim, String> features(Sample s, ZoneId zone) {
        return features(s.kind().name(), s.textLength(), s.hasLink(), s.postedAt().atZone(zone).getHour());
    }

    /** @param hour null when the time isn't chosen yet */
    public static Map<Dim, String> features(String kind, int textLength, boolean link, Integer hour) {
        Map<Dim, String> f = new EnumMap<>(Dim.class);
        f.put(Dim.FORMAT, kind);
        f.put(Dim.LENGTH, AnalysisEngine.lengthBucket(textLength));
        f.put(Dim.LINK, link ? "LINK" : "NOLINK");
        if (hour != null) f.put(Dim.TIME, timeKey(hour));
        return f;
    }

    /** @param v each post's log result against its neighbours, in the same order as {@code samples} */
    public static PostModel fit(List<Sample> samples, double[] v, ZoneId zone) {
        int n = samples.size();
        List<Map<Dim, String>> rows = new ArrayList<>(n);
        SortedSet<String> cols = new TreeSet<>();
        for (Sample s : samples) {
            Map<Dim, String> f = features(s, zone);
            rows.add(f);
            f.forEach((d, k) -> cols.add(d + ":" + k));
        }
        List<String> columns = new ArrayList<>(cols);
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) idx.put(columns.get(i), i);
        int p = columns.size();
        int[][] x = new int[n][];
        int[] counts = new int[p];
        for (int i = 0; i < n; i++) {
            Map<Dim, String> f = rows.get(i);
            x[i] = new int[f.size()];
            int j = 0;
            for (var e : f.entrySet()) {
                int c = idx.get(e.getKey() + ":" + e.getValue());
                x[i][j++] = c;
                counts[c]++;
            }
        }
        double mean = n == 0 ? 0 : Arrays.stream(v, 0, n).average().orElse(0);
        double[][] xtx = new double[p][p];
        double[] xty = new double[p];
        for (int i = 0; i < n; i++) {
            double y = v[i] - mean;
            for (int a : x[i]) {
                xty[a] += y;
                for (int b : x[i]) xtx[a][b]++;
            }
        }
        double[][] reg = new double[p][p];
        for (int a = 0; a < p; a++) {
            reg[a] = xtx[a].clone();
            reg[a][a] += LAMBDA;
        }
        double[][] inv = invert(reg);
        double[] beta = mul(inv, xty);

        double rss = 0;
        for (int i = 0; i < n; i++) {
            double pred = 0;
            for (int a : x[i]) pred += beta[a];
            double r = v[i] - mean - pred;
            rss += r * r;
        }
        // effective degrees of freedom of a ridge fit: trace((X'X + λI)^-1 X'X)
        double df = 0;
        double[][] ixtx = mul(inv, xtx);
        for (int a = 0; a < p; a++) df += ixtx[a][a];
        double sigma2 = rss / Math.max(1, n - df);
        double[][] cov = mul(ixtx, inv);
        for (double[] row : cov) for (int b = 0; b < p; b++) row[b] *= sigma2;
        return new PostModel(columns, counts, beta, cov, n);
    }

    public int posts() { return posts; }

    /** The log result the model expects for these traits, against a typical post (0 = typical). */
    public double predict(Map<Dim, String> f) {
        double out = 0;
        for (int c : cols(f)) out += beta[c];
        return out;
    }

    /** The standard error of {@link #predict}. */
    public double predictSe(Map<Dim, String> f) {
        int[] c = cols(f);
        double var = 0;
        for (int a : c) for (int b : c) var += cov[a][b];
        return Math.sqrt(Math.max(0, var));
    }

    public Optional<Effect> effect(Dim d, String key) {
        Integer c = index.get(d + ":" + key);
        return c == null ? Optional.empty() : Optional.of(effect(d, key, c));
    }

    /** Every value of a trait the channel has used, best first. */
    public List<Effect> effects(Dim d) {
        List<Effect> out = new ArrayList<>();
        for (int c = 0; c < columns.size(); c++) {
            String col = columns.get(c);
            if (col.startsWith(d + ":")) out.add(effect(d, col.substring(d.name().length() + 1), c));
        }
        out.sort(Comparator.comparingDouble(Effect::lift).reversed());
        return out;
    }

    /** The standard error of the difference between two values of the same trait. */
    public double diffSe(Dim d, String a, String b) {
        Integer i = index.get(d + ":" + a), j = index.get(d + ":" + b);
        if (i == null || j == null) return Double.POSITIVE_INFINITY;
        return Math.sqrt(Math.max(0, cov[i][i] + cov[j][j] - 2 * cov[i][j]));
    }

    public double beta(Dim d, String key) {
        Integer c = index.get(d + ":" + key);
        return c == null ? 0 : beta[c];
    }

    private Effect effect(Dim d, String key, int c) {
        double se = Math.sqrt(Math.max(0, cov[c][c]));
        return new Effect(d, key, counts[c], Math.exp(beta[c]), Math.exp(beta[c] - Z90 * se), Math.exp(beta[c] + Z90 * se));
    }

    private int[] cols(Map<Dim, String> f) {
        return f.entrySet().stream().map(e -> index.get(e.getKey() + ":" + e.getValue())).filter(Objects::nonNull)
                .mapToInt(Integer::intValue).toArray();
    }

    // ------------------------------------------------------------------ linear algebra

    /** Gauss–Jordan with partial pivoting; the ridge term keeps the matrix well away from singular. */
    static double[][] invert(double[][] m) {
        int n = m.length;
        double[][] a = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(m[i], 0, a[i], 0, n);
            a[i][n + i] = 1;
        }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++) if (Math.abs(a[r][col]) > Math.abs(a[piv][col])) piv = r;
            double[] t = a[col]; a[col] = a[piv]; a[piv] = t;
            double d = a[col][col];
            for (int k = 0; k < 2 * n; k++) a[col][k] /= d;
            for (int r = 0; r < n; r++) {
                if (r == col || a[r][col] == 0) continue;
                double f = a[r][col];
                for (int k = 0; k < 2 * n; k++) a[r][k] -= f * a[col][k];
            }
        }
        double[][] out = new double[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(a[i], n, out[i], 0, n);
        return out;
    }

    private static double[] mul(double[][] m, double[] v) {
        double[] out = new double[m.length];
        for (int i = 0; i < m.length; i++) for (int j = 0; j < v.length; j++) out[i] += m[i][j] * v[j];
        return out;
    }

    private static double[][] mul(double[][] a, double[][] b) {
        int n = a.length, k = b.length, m = k == 0 ? 0 : b[0].length;
        double[][] out = new double[n][m];
        for (int i = 0; i < n; i++) for (int j = 0; j < k; j++) {
            if (a[i][j] == 0) continue;
            for (int c = 0; c < m; c++) out[i][c] += a[i][j] * b[j][c];
        }
        return out;
    }
}
