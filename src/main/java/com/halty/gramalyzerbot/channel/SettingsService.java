package com.halty.gramalyzerbot.channel;

import com.halty.gramalyzerbot.analytics.Metric;
import com.halty.gramalyzerbot.common.UserFacingException;
import com.halty.gramalyzerbot.crm.CrmBridge;
import com.halty.gramalyzerbot.post.AnalysisService;
import com.halty.gramalyzerbot.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;

/** Validated, permission-checked, audited changes to a channel's settings. */
@Service
@RequiredArgsConstructor
public class SettingsService {

    private static final Set<Integer> WINDOWS = Set.of(0, 30, 90, 180);
    private static final Set<Integer> LEADS = Set.of(15, 30, 60);
    private static final Set<Integer> GAPS = Set.of(3, 6, 12, 24);

    private final ChannelService channels;
    private final MembershipService membership;
    private final AnalysisService analysis;
    private final AuditService audit;
    private final CrmBridge crm;

    public Channel setTimezone(Channel ch, User actor, ZoneId zone) {
        guard(ch, actor);
        ch.setTimezone(zone.getId());
        ch.setTimezoneConfirmed(true);
        Channel saved = done(ch, actor, "settings.timezone", "Timezone → " + TimeZones.label(zone, Instant.now()));
        crm.pushTimezone(saved); // the website shows the same setting
        return saved;
    }

    public Channel setWindow(Channel ch, User actor, int days) {
        guard(ch, actor);
        if (!WINDOWS.contains(days)) throw new UserFacingException("err.bad_choice");
        ch.setWindowDays(days);
        return done(ch, actor, "settings.window", "Analysis window → " + (days == 0 ? "all time" : days + " days"));
    }

    /** Views are read from the public web preview, so only public channels (and samples) have them. */
    public static boolean viewsKnown(Channel ch) { return ch.isDemo() || (ch.getUsername() != null && !ch.getUsername().isBlank()); }

    public Channel setMetric(Channel ch, User actor, Metric metric) {
        guard(ch, actor);
        if (metric == Metric.VIEWS && !viewsKnown(ch)) throw new UserFacingException("met.err.views");
        ch.setMetric(metric);
        return done(ch, actor, "settings.metric", "Success metric → " + metric.label());
    }

    public Channel setDigestDay(Channel ch, User actor, DayOfWeek day) {
        guard(ch, actor);
        ch.setDigestDay(day);
        return done(ch, actor, "settings.digest", "Digest day → " + day);
    }

    public Channel setDigestHour(Channel ch, User actor, int hour) {
        guard(ch, actor);
        if (hour < 0 || hour > 23) throw new UserFacingException("err.bad_hour");
        ch.setDigestHour(hour);
        return done(ch, actor, "settings.digest", "Digest hour → " + String.format("%02d:00", hour));
    }

    public Channel setNudgeLead(Channel ch, User actor, int minutes) {
        guard(ch, actor);
        if (!LEADS.contains(minutes)) throw new UserFacingException("err.bad_choice");
        ch.setNudgeLeadMinutes(minutes);
        return done(ch, actor, "settings.nudge", "Nudge lead time → " + minutes + " min");
    }

    public Channel setNudgeGap(Channel ch, User actor, int hours) {
        guard(ch, actor);
        if (!GAPS.contains(hours)) throw new UserFacingException("err.bad_choice");
        ch.setNudgeGapHours(hours);
        return done(ch, actor, "settings.nudge", "Nudge quiet period → " + hours + " h");
    }

    public Channel linkDiscussion(Channel ch, User actor, long groupChatId) {
        guard(ch, actor);
        ch.setDiscussionChatId(groupChatId);
        return done(ch, actor, "settings.comments", "Comments tracking switched on");
    }

    private void guard(Channel ch, User actor) { membership.require(ch, actor, Permission.CHANGE_SETTINGS); }

    private Channel done(Channel ch, User actor, String action, String details) {
        Channel saved = channels.save(ch);
        analysis.invalidate(saved.getId());
        audit.record(saved.getId(), actor.getId(), action, details);
        return saved;
    }
}
