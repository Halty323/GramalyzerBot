package com.halty.gramalyzerbot.bot.screen;

import com.halty.gramalyzerbot.bot.ui.*;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.Permission;
import com.halty.gramalyzerbot.channel.Role;
import com.halty.gramalyzerbot.goals.GoalMath;
import com.halty.gramalyzerbot.goals.GoalService;
import com.halty.gramalyzerbot.i18n.Tr;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;

/** The subscriber goal with its pace, and the weekly posting streak. */
@Component
@RequiredArgsConstructor
public class GoalScreens {

    static final int[] STREAK_TARGETS = {1, 3, 5, 7};

    private final GoalService goals;

    public static String day(LocalDate d) {
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Tr.locale()).format(d);
    }

    public View home(Channel ch, Role role) {
        GoalService.Status s = goals.status(ch);
        long id = ch.getId();
        boolean edit = role.can(Permission.CHANGE_SETTINGS);
        View v = new View().line(Tr.t("goal.title")).line("<i>" + Fmt.esc(ch.getTitle()) + "</i>").blank();
        GoalMath.Progress g = s.goal();
        if (g != null) {
            for (String l : progress(g)) v.line(l);
        } else if (s.subscribers() == null) {
            v.line(Tr.t("goal.no_count"));
        } else {
            v.line(Tr.t("goal.none", Fmt.num(s.subscribers())));
        }
        v.blank();
        for (String l : streak(s.streak())) v.line(l);

        if (edit) {
            if (g == null) {
                for (GoalService.Suggestion x : s.suggestions())
                    v.row(Btn.cb(Tr.t("goal.btn.preset", Fmt.signed(x.gain()), day(x.deadline())), Cb.of("goal:set", id, x.target(), x.deadline().toEpochDay())));
                v.row(Btn.cb(Tr.t("goal.btn.own"), Cb.of("goal:new", id)));
            } else {
                v.row(Btn.cb(Tr.t("goal.btn.change"), Cb.of("goal:new", id)), Btn.cb(Tr.t("goal.btn.remove"), Cb.of("goal:del", id)));
            }
            List<Btn> targets = new ArrayList<>();
            for (int n : STREAK_TARGETS)
                targets.add(Btn.cb((n == ch.getStreakTarget() ? "✓ " : "") + Tr.t("goal.btn.per_week", n), Cb.of("goal:ppw", id, n)));
            v.row(targets.toArray(Btn[]::new));
        }
        return v.row(Btn.cb(Tr.t("btn.back"), Cb.of("nav:home", id)));
    }

    /** The goal in a few lines: target, progress bar, verdict, pace. Also used by the weekly digest. */
    public static List<String> progress(GoalMath.Progress g) {
        List<String> out = new ArrayList<>();
        out.add(Tr.t("goal.target", Fmt.num(g.target()), day(g.deadline())));
        out.add(Fmt.progress(g.percent(), 100, 10) + " " + g.percent() + "% · " + Tr.t("goal.now", g.current() == null ? "—" : Fmt.num(g.current()), Fmt.signed(g.gained())));
        out.add(switch (g.state()) {
            case REACHED -> Tr.t("goal.reached");
            case MISSED -> Tr.t("goal.missed", Fmt.num(g.needed()));
            case NO_DATA -> Tr.t("goal.no_pace", Fmt.num(g.needed()), Tr.n("n.days", g.daysLeft()));
            case ON_TRACK -> Tr.t("goal.on_track", g.eta() == null ? "—" : day(g.eta()));
            case BEHIND -> Tr.t("goal.behind", Fmt.num(g.projected()));
        });
        if (g.perDay() != null && (g.state() == GoalMath.State.ON_TRACK || g.state() == GoalMath.State.BEHIND))
            out.add(Tr.t("goal.pace", signedOne(g.perDay()), signedOne(g.needPerDay())));
        return out;
    }

    /** The streak in two or three lines. Also used by the weekly digest. */
    public static List<String> streak(GoalMath.Streak s) {
        List<String> out = new ArrayList<>();
        out.add(s.weeks() > 0 ? Tr.t("goal.streak", Tr.n("n.weeks", s.weeks()), s.target()) : Tr.t("goal.streak_none", s.target()));
        StringBuilder squares = new StringBuilder();
        for (int i = 0; i < s.recent().size(); i++) {
            int n = s.recent().get(i);
            boolean now = i == s.recent().size() - 1;
            squares.append(n >= s.target() ? "🟩" : now ? "⬜" : n > 0 ? "🟨" : "🟥");
        }
        if (s.recent().stream().anyMatch(n -> n > 0)) out.add(squares + (s.best() > 0 ? " " + Tr.t("goal.best", Tr.n("n.weeks", s.best())) : ""));
        if (s.thisWeek() >= s.target()) out.add(Tr.t("goal.week_done", s.thisWeek()));
        else out.add(Tr.t(s.atRisk() ? "goal.week_risk" : "goal.week_left", s.thisWeek(), s.target(), s.missing(), Tr.n("n.days", s.daysLeft())));
        return out;
    }

    public View prompt(Channel ch, Integer current) {
        return new View().line(Tr.t("goal.prompt_title")).blank()
                .line(Tr.t("goal.prompt", current == null ? "—" : Fmt.num(current))).line(Tr.t("goal.prompt_example"))
                .row(Btn.cb(Tr.t("btn.cancel"), Cb.of("goal:show", ch.getId())));
    }

    private static String signedOne(double x) {
        return (x > 0 ? "+" : x < 0 ? "−" : "±") + Fmt.one(Math.abs(x)).replace(".0", "");
    }
}
