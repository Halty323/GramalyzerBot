package com.halty.gramalyzerbot.bot.ui;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;

/** Weekday × time-of-day grid drawn with coloured squares (2-hour columns), meant for a &lt;pre&gt; block. */
public final class Heatmap {

    private Heatmap() {}

    public static String render(double[][] lift, int[][] posts) {
        double[][] block = new double[7][12];
        boolean[][] has = new boolean[7][12];
        List<Double> seen = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            for (int b = 0; b < 12; b++) {
                int n = posts[d][2 * b] + posts[d][2 * b + 1];
                has[d][b] = n > 0;
                block[d][b] = (lift[d][2 * b] + lift[d][2 * b + 1]) / 2.0;
                if (has[d][b]) seen.add(block[d][b]);
            }
        }
        double lo = 0, hi = 1;
        boolean flat = true;
        if (!seen.isEmpty()) {
            List<Double> sorted = seen.stream().sorted().toList();
            lo = sorted.get((int) Math.floor((sorted.size() - 1) * 0.05));
            hi = sorted.get((int) Math.ceil((sorted.size() - 1) * 0.95));
            flat = hi - lo < 0.15;
        }
        StringBuilder sb = new StringBuilder("    ");
        for (int b = 0; b < 12; b += 3) sb.append(String.format("%02d", 2 * b)).append("    ");
        sb.setLength(sb.toString().stripTrailing().length());
        sb.append('\n');
        for (int d = 0; d < 7; d++) {
            sb.append(pad(Fmt.dayShort(DayOfWeek.of(d + 1)))).append(' ');
            for (int b = 0; b < 12; b++) {
                if (!has[d][b]) sb.append("⬜");
                else if (flat) sb.append("🟨");
                else sb.append(level(block[d][b], lo, hi));
            }
            if (d < 6) sb.append('\n');
        }
        return sb.toString();
    }

    /** The colour key, in the current language. */
    public static String legend() { return com.halty.gramalyzerbot.i18n.Tr.t("heatmap.legend"); }

    /** Day names are padded to three characters so the grid lines up in any language. */
    private static String pad(String day) {
        int n = day.codePointCount(0, day.length());
        if (n >= 3) return day.substring(0, day.offsetByCodePoints(0, 3));
        return day + " ".repeat(3 - n);
    }

    /** Range-scaled colour; a cell is only "strong" if above typical (1.0) and only "weak" if below it. */
    private static String level(double x, double lo, double hi) {
        double t = Math.max(0, Math.min(1, (x - lo) / (hi - lo)));
        if (t >= 0.7 && x > 1.0) return "🟩";
        if (t >= 0.4) return "🟨";
        if (t >= 0.15 || x >= 1.0) return "🟧";
        return "🟥";
    }
}
