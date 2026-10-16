package com.halty.gramalyzerbot.bot.ui;

import com.halty.gramalyzerbot.analytics.Report;
import com.halty.gramalyzerbot.analytics.Report.Group;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Turns a {@link Report} into the few plain-language recommendations shown on the dashboard. */
public final class Playbook {

    private static final double WIN = 1.1, LOSE = 0.8;

    private Playbook() {}

    public static List<String> lines(Report r) {
        List<String> out = new ArrayList<>();
        if (r.eligible() < 10 || !r.hasSignal()) return out;

        if (!r.topSlots().isEmpty()) {
            StringBuilder sb = new StringBuilder("⏰ <b>Post on</b> ");
            int n = Math.min(3, r.topSlots().size());
            for (int i = 0; i < n; i++) {
                Report.Slot s = r.topSlots().get(i);
                if (i > 0) sb.append(" · ");
                sb.append(Fmt.dayShort(s.day())).append(' ').append(Fmt.hour(s.hour()));
            }
            out.add(sb.toString());
        }
        best(r.kinds()).ifPresent(g -> out.add("🎯 <b>Format:</b> " + Labels.kind(g.key()) + " (" + Fmt.lift(g.lift()) + ")"));
        List<Group> winners = r.topics().stream().filter(g -> g.lift() >= WIN).limit(2).toList();
        if (!winners.isEmpty()) {
            StringBuilder sb = new StringBuilder("🏷 <b>Topics:</b> ");
            for (int i = 0; i < winners.size(); i++) {
                if (i > 0) sb.append(" · ");
                sb.append(Labels.topic(winners.get(i).key())).append(" (").append(Fmt.lift(winners.get(i).lift())).append(')');
            }
            out.add(sb.toString());
        }
        best(r.lengths()).ifPresent(g -> out.add("📏 <b>Length:</b> " + Labels.length(g.key()) + " (" + Fmt.lift(g.lift()) + ")"));
        r.topics().stream().filter(g -> g.lift() <= LOSE).min(Comparator.comparingDouble(Group::lift))
                .ifPresent(g -> out.add("🧊 <b>Go easier on:</b> " + Labels.topic(g.key()) + " (" + Fmt.lift(g.lift()) + ")"));
        return out;
    }

    private static java.util.Optional<Group> best(List<Group> groups) {
        return groups.stream().filter(g -> g.lift() >= WIN).max(Comparator.comparingDouble(Group::lift));
    }
}
