package com.halty.gramalyzerbot.bot.screen;

import com.halty.gramalyzerbot.bot.ui.*;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.i18n.Tr;
import com.halty.gramalyzerbot.seo.SearchCheck;
import com.halty.gramalyzerbot.seo.SearchCheckService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.stream.Collectors;

/** How findable a channel is in Telegram's search, and what to change. */
@Component
@RequiredArgsConstructor
public class SearchCheckScreens {

    private final SearchCheckService search;

    public View show(Channel ch) {
        long id = ch.getId();
        SearchCheck.Result r = search.check(ch);
        View v = new View().line(Tr.t("seo.title")).line("<i>" + Fmt.esc(ch.getTitle()) + "</i>").blank()
                .line(Tr.t("seo.score", r.score(), Tr.t("seo.grade." + r.grade())))
                .line(Fmt.progress(r.score(), 100, 10));
        String group = null;
        for (SearchCheck.Check c : r.checks()) {
            if (r.username() == null && c.id().startsWith("username_")) continue; // "make it public" already says it
            if (!c.group().equals(group)) {
                group = c.group();
                v.blank().line(Tr.t("seo.group." + group, group.equals("find") ? r.find() : r.join()));
            }
            String icon = switch (c.status()) { case OK -> "✅"; case WARN -> "⚠️"; case BAD -> "❌"; };
            String words = c.words().stream().map(w -> "«" + Fmt.esc(w) + "»").collect(Collectors.joining(", "));
            v.line(icon + " " + Tr.t("seo." + c.id() + "." + c.status().name().toLowerCase(Locale.ROOT), words));
        }
        if (!r.keywords().isEmpty()) {
            v.blank().line(Tr.t("seo.words", r.keywords().stream()
                    .map(k -> Fmt.esc(k.word()) + (k.inTitle() ? " ✓" : "")).collect(Collectors.joining(", "))));
        }
        if (!r.titleIdeas().isEmpty()) {
            v.blank().line(Tr.t("seo.title_ideas"));
            r.titleIdeas().forEach(t -> v.line("• <code>" + Fmt.esc(t) + "</code>"));
        }
        if (!r.usernameIdeas().isEmpty()) {
            v.blank().line(Tr.t("seo.username_ideas"));
            v.line(r.usernameIdeas().stream().map(u -> "<code>@" + Fmt.esc(u) + "</code>").collect(Collectors.joining(" · ")));
        }
        if (!r.positions().isEmpty()) {
            v.blank().line(Tr.t("seo.positions"));
            for (SearchCheck.Position p : r.positions()) {
                String now = p.position() == null ? Tr.t("seo.position.none") : "#" + p.position();
                String before = p.previous() == null ? "" : " <i>(" + Tr.t("seo.position.before", "#" + p.previous()) + ")</i>";
                v.line("• «" + Fmt.esc(p.word()) + "» " + now + before + " · " + Fmt.date(p.checkedAt()));
            }
        }
        v.blank().line(Tr.t("seo.notes"));
        for (String n : r.notes()) v.line("• " + Tr.t("seo.note." + n));
        return v.row(Btn.cb(Tr.t("btn.refresh"), Cb.of("seo:show", id)), Btn.cb(Tr.t("btn.dashboard"), Cb.of("nav:home", id)));
    }
}
