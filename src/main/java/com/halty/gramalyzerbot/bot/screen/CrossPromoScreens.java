package com.halty.gramalyzerbot.bot.screen;

import com.halty.gramalyzerbot.bot.ui.*;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.Permission;
import com.halty.gramalyzerbot.channel.Role;
import com.halty.gramalyzerbot.crosspromo.Swap;
import com.halty.gramalyzerbot.crosspromo.SwapProfile;
import com.halty.gramalyzerbot.crosspromo.SwapService;
import com.halty.gramalyzerbot.crosspromo.SwapService.Match;
import com.halty.gramalyzerbot.crosspromo.SwapService.Notice;
import com.halty.gramalyzerbot.crosspromo.SwapService.SwapView;
import com.halty.gramalyzerbot.crosspromo.Topic;
import com.halty.gramalyzerbot.growth.GrowthService.LinkStat;
import com.halty.gramalyzerbot.i18n.Lang;
import com.halty.gramalyzerbot.i18n.Tr;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Cross-promo: the channel's profile, partners that fit, and swaps with their results. */
@Component
@RequiredArgsConstructor
public class CrossPromoScreens {

    private static final int SHOWN_SWAPS = 8;
    private static final Map<Topic, String> EMOJI = Map.ofEntries(
            Map.entry(Topic.TECH, "💻"), Map.entry(Topic.SCIENCE, "🔬"), Map.entry(Topic.GAMING, "🎮"),
            Map.entry(Topic.BUSINESS, "💼"), Map.entry(Topic.FINANCE, "💰"), Map.entry(Topic.MARKETING, "📣"),
            Map.entry(Topic.DESIGN, "🎨"), Map.entry(Topic.ART, "🖼"), Map.entry(Topic.PHOTO, "📷"),
            Map.entry(Topic.EDUCATION, "🎓"), Map.entry(Topic.PARENTING, "👶"), Map.entry(Topic.NEWS, "📰"),
            Map.entry(Topic.LIFESTYLE, "🌿"), Map.entry(Topic.TRAVEL, "✈️"), Map.entry(Topic.FOOD, "🍳"),
            Map.entry(Topic.HEALTH, "🏃"), Map.entry(Topic.FASHION, "👗"), Map.entry(Topic.ENTERTAINMENT, "🍿"),
            Map.entry(Topic.MUSIC, "🎵"), Map.entry(Topic.SPORTS, "⚽"), Map.entry(Topic.MOVIES, "🎬"), Map.entry(Topic.OTHER, "✨"));

    private final SwapService swaps;

    public static String topic(Topic t) { return t == null ? "–" : EMOJI.get(t) + " " + Tr.t("topic." + t.name()); }

    private static String language(String code) { return Lang.fromCode(code).map(Lang::label).orElse(code); }

    public View home(Channel ch, Role role) {
        long id = ch.getId();
        boolean manage = role.can(Permission.CHANGE_SETTINGS);
        View v = new View().line(Tr.t("swap.title")).line("<i>" + Fmt.esc(ch.getTitle()) + "</i>").blank();
        Optional<SwapProfile> profile = swaps.profile(ch);
        if (profile.isEmpty()) {
            v.line(Tr.t("swap.intro")).blank().line(Tr.t(manage ? "swap.join" : "swap.join_ask_admin"));
            if (manage) v.row(Btn.cb(Tr.t("swap.btn.start"), Cb.of("sw:topic", id)));
            return v.row(Btn.cb(Tr.t("btn.back"), Cb.of("nav:growth", id, 30)));
        }
        SwapProfile p = profile.get();
        v.line(Tr.t("swap.profile", topic(p.getTopic()), language(p.getLanguage())));
        if (p.getPitch() != null) v.line(Tr.t("swap.pitch", Fmt.esc(p.getPitch())));
        else if (manage) v.line("<i>" + Tr.t("swap.no_pitch") + "</i>");
        if (!p.isEnabled()) v.blank().line(Tr.t("swap.paused"));

        List<SwapView> all = swaps.swaps(ch);
        v.blank().line(Tr.t("swap.list"));
        if (all.isEmpty()) v.line(Tr.t("swap.empty"));
        List<Btn> open = new ArrayList<>();
        for (int i = 0; i < Math.min(SHOWN_SWAPS, all.size()); i++) {
            SwapView s = all.get(i);
            v.line((i + 1) + ". " + row(s));
            open.add(Btn.cb((s.incoming() ? "📬 " : "") + (i + 1), Cb.of("sw:view", id, s.id())));
        }
        for (int i = 0; i < open.size(); i += 4) v.row(open.subList(i, Math.min(i + 4, open.size())).toArray(new Btn[0]));

        if (manage) {
            if (p.isEnabled()) v.row(Btn.cb(Tr.t("swap.btn.find"), Cb.of("sw:find", id)));
            v.row(Btn.cb(Tr.t("swap.btn.topic"), Cb.of("sw:topic", id)), Btn.cb(Tr.t("swap.btn.pitch"), Cb.of("sw:pitch", id)));
            v.row(Btn.cb(Tr.t(p.isEnabled() ? "swap.btn.pause" : "swap.btn.resume"), Cb.of(p.isEnabled() ? "sw:off" : "sw:on", id)));
        }
        return v.row(Btn.cb(Tr.t("btn.back"), Cb.of("nav:growth", id, 30)));
    }

    private static String row(SwapView s) {
        String name = "<b>" + Fmt.esc(s.partnerTitle()) + "</b>";
        return switch (s.status()) {
            case REQUESTED -> Tr.t(s.outgoing() ? "swap.row.outgoing" : "swap.row.incoming", name);
            case ACTIVE -> Tr.t("swap.row.active", name, Fmt.num(joined(s.mine())), Fmt.num(joined(s.theirs())));
            case DONE -> Tr.t("swap.row.done", name, Fmt.num(joined(s.mine())), Fmt.num(joined(s.theirs())));
            case DECLINED -> Tr.t(s.outgoing() ? "swap.row.declined" : "swap.row.you_declined", name);
            case CANCELLED -> Tr.t("swap.row.cancelled", name);
        };
    }

    private static int joined(LinkStat l) { return l == null ? 0 : l.joined(); }

    public View topics(Channel ch, Topic current) {
        View v = new View().line(Tr.t("swap.topic_title")).line(Tr.t("swap.topic_hint"));
        List<Btn> row = new ArrayList<>();
        for (Topic t : Topic.values()) {
            row.add(Btn.cb((t == current ? "✅ " : "") + topic(t), Cb.of("sw:settopic", ch.getId(), t.name())));
            if (row.size() == 2) {
                v.row(row.toArray(new Btn[0]));
                row.clear();
            }
        }
        if (!row.isEmpty()) v.row(row.toArray(new Btn[0]));
        return v.row(Btn.cb(Tr.t("btn.back"), Cb.of("sw:home", ch.getId())));
    }

    public View languages(Channel ch, Topic topic, String current) {
        View v = new View().line(Tr.t("swap.lang_title")).line(Tr.t("swap.lang_hint"));
        List<Btn> row = new ArrayList<>();
        for (Lang l : Lang.values()) {
            row.add(Btn.cb((l.code().equals(current) ? "✅ " : "") + l.label(), Cb.of("sw:setlang", ch.getId(), topic.name(), l.code())));
            if (row.size() == 2) {
                v.row(row.toArray(new Btn[0]));
                row.clear();
            }
        }
        if (!row.isEmpty()) v.row(row.toArray(new Btn[0]));
        return v.row(Btn.cb(Tr.t("btn.back"), Cb.of("sw:topic", ch.getId())));
    }

    public View pitchPrompt(Channel ch) {
        return new View().line(Tr.t("swap.pitch_title")).blank().line(Tr.t("swap.pitch_text"))
                .row(Btn.cb(Tr.t("btn.cancel"), Cb.of("sw:home", ch.getId())));
    }

    public View matches(Channel ch) {
        long id = ch.getId();
        List<Match> list = swaps.matches(ch);
        View v = new View().line(Tr.t("swap.matches_title", Fmt.esc(ch.getTitle()))).blank();
        if (list.isEmpty()) {
            v.line(Tr.t("swap.matches_empty"));
        } else {
            v.line("<i>" + Tr.t("swap.matches_hint") + "</i>");
            List<Btn> ask = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                Match m = list.get(i);
                v.blank().line(Tr.t("swap.match", i + 1, Fmt.esc(m.title()), m.score()));
                v.line(topic(m.topic()) + (m.subscribers() == null ? "" : " · 👥 " + Fmt.compact(m.subscribers()))
                        + (m.engagement() == null ? "" : " · " + Tr.t("swap.engagement", Fmt.one(m.engagement()))));
                if (m.pitch() != null) v.line("<i>" + Fmt.esc(m.pitch()) + "</i>");
                ask.add(Btn.cb("🤝 " + (i + 1), Cb.of("sw:ask", id, m.channelId())));
            }
            for (int i = 0; i < ask.size(); i += 5) v.row(ask.subList(i, Math.min(i + 5, ask.size())).toArray(new Btn[0]));
        }
        return v.row(Btn.cb(Tr.t("btn.back"), Cb.of("sw:home", id)));
    }

    public View swap(Channel ch, Role role, SwapView s) {
        return swap(ch, role, s, null);
    }

    /** One swap; {@code notice} is the line on top when the other side just did something. */
    public View swap(Channel ch, Role role, SwapView s, Notice.Kind notice) {
        long id = ch.getId();
        String name = Fmt.esc(s.partnerTitle());
        View v = new View();
        if (notice != null) v.line(Tr.t("swap.n." + notice.name().toLowerCase(Locale.ROOT), name, Fmt.esc(ch.getTitle()))).blank();
        else v.line(Tr.t("swap.v." + state(s), name)).blank();
        v.line(topic(s.partnerTopic()) + (s.partnerSubscribers() == null ? "" : " · 👥 " + Fmt.compact(s.partnerSubscribers())));
        if (s.partnerPitch() != null) v.line("<i>" + Fmt.esc(s.partnerPitch()) + "</i>");
        if (s.partnerUrl() != null) v.line(Tr.t("swap.v.channel", s.partnerUrl()));
        if (s.partnerContact() != null) v.line(Tr.t("swap.v.contact", Fmt.esc(s.partnerContact())));

        if (s.status() == Swap.Status.ACTIVE || s.status() == Swap.Status.DONE) {
            if (s.theirs() != null) {
                v.blank().line(Tr.t("swap.v.their_link"));
                if (s.status() == Swap.Status.ACTIVE) v.line("<code>" + Fmt.esc(s.theirs().url()) + "</code>");
                v.line(Tr.t("swap.v.sent", Fmt.num(s.theirs().joined()), Fmt.num(s.theirs().stayed())));
            }
            if (s.mine() != null) {
                v.blank().line(Tr.t("swap.v.your_link"));
                if (s.status() == Swap.Status.ACTIVE) v.line("<code>" + Fmt.esc(s.mine().url()) + "</code>");
                v.line(Tr.t("swap.v.brought", Fmt.num(s.mine().joined()), Fmt.num(s.mine().stayed())));
            }
            if (s.status() == Swap.Status.ACTIVE) v.blank().line("<i>" + Tr.t("swap.v.agree") + "</i>");
        }

        if (role.can(Permission.CHANGE_SETTINGS)) {
            if (s.incoming()) v.row(Btn.cb(Tr.t("swap.btn.accept"), Cb.of("sw:ok", id, s.id())), Btn.cb(Tr.t("swap.btn.decline"), Cb.of("sw:no", id, s.id())));
            else if (s.status() == Swap.Status.REQUESTED) v.row(Btn.cb(Tr.t("swap.btn.cancel"), Cb.of("sw:cx", id, s.id())));
            else if (s.status() == Swap.Status.ACTIVE) v.row(Btn.cb(Tr.t("swap.btn.end"), Cb.of("sw:end", id, s.id())));
        }
        return v.row(Btn.cb(Tr.t("swap.btn.list"), Cb.of("sw:home", id)));
    }

    private static String state(SwapView s) {
        if (s.status() == Swap.Status.REQUESTED) return s.outgoing() ? "outgoing" : "incoming";
        if (s.status() == Swap.Status.DECLINED && !s.outgoing()) return "you_declined";
        return s.status().name().toLowerCase(Locale.ROOT);
    }
}
