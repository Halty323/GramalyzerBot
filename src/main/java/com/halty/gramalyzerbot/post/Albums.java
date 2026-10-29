package com.halty.gramalyzerbot.post;

import com.halty.gramalyzerbot.analytics.PostKind;
import com.halty.gramalyzerbot.analytics.Sample;

import java.util.*;

/** Telegram stores every item of an album as its own message; for analysis an album is one post. */
public final class Albums {

    private Albums() {}

    public static List<Sample> collapse(List<Sample> in) {
        Map<String, List<Sample>> groups = new LinkedHashMap<>();
        List<Sample> out = new ArrayList<>();
        for (Sample s : in) {
            if (s.mediaGroupId() == null) out.add(s);
            else groups.computeIfAbsent(s.mediaGroupId(), k -> new ArrayList<>()).add(s);
        }
        for (List<Sample> g : groups.values()) {
            if (g.size() == 1) { out.add(g.get(0)); continue; }
            g.sort(Comparator.comparing(Sample::postedAt).thenComparingInt(Sample::messageId));
            Sample first = g.get(0);
            String text = "";
            boolean link = false, excluded = false, known = false;
            int reactions = 0, comments = 0;
            Integer views = null; // every part of an album shows the same count; take the highest reading
            Set<String> topics = new LinkedHashSet<>();
            for (Sample s : g) {
                if (text.isEmpty() && !s.text().isEmpty()) text = s.text();
                link |= s.hasLink();
                excluded |= s.excluded();
                known |= s.commentsKnown();
                reactions += s.reactions();
                comments += s.comments();
                topics.addAll(s.topics());
                if (s.views() != null && (views == null || s.views() > views)) views = s.views();
            }
            out.add(new Sample(first.id(), first.messageId(), first.postedAt(), PostKind.ALBUM, text, link,
                    new ArrayList<>(topics), reactions, comments, known, excluded, first.mediaGroupId(), views));
        }
        out.sort(Comparator.comparing(Sample::postedAt).thenComparingInt(Sample::messageId));
        return out;
    }
}
