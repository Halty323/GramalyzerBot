package com.halty.gramalyzerbot.goals;

import com.halty.gramalyzerbot.analytics.Sample;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.ChannelService;
import com.halty.gramalyzerbot.channel.SubscriberSnapshot;
import com.halty.gramalyzerbot.channel.SubscriberSnapshotRepository;
import com.halty.gramalyzerbot.common.UserFacingException;
import com.halty.gramalyzerbot.post.AnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Subscriber goals with their pace, and the weekly posting streak. */
@Service
@RequiredArgsConstructor
public class GoalService {

    static final int MAX_STREAK_TARGET = 21;
    static final int MAX_YEARS = 2;

    /** @param goal null when the channel has none */
    public record Status(Integer subscribers, GoalMath.Progress goal, GoalMath.Streak streak, List<Suggestion> suggestions) {}

    /** A goal worth offering: about a third faster than the recent pace, rounded. */
    public record Suggestion(int target, LocalDate deadline, int gain) {}

    public record Parsed(int target, LocalDate deadline) {}

    private final GoalRepository goals;
    private final SubscriberSnapshotRepository snapshots;
    private final AnalysisService analysis;
    private final ChannelService channels;

    public Optional<Goal> goal(Channel ch) { return goals.findById(ch.getId()); }

    public Status status(Channel ch) {
        Instant now = Instant.now();
        Integer current = current(ch);
        List<GoalMath.Reading> readings = readings(ch, now);
        GoalMath.Progress progress = goal(ch).map(g -> GoalMath.progress(g, current, readings, now, ch.zone())).orElse(null);
        return new Status(current, progress, streak(ch, now), progress == null ? suggestions(ch, current, readings, now) : List.of());
    }

    public GoalMath.Streak streak(Channel ch, Instant now) {
        List<Instant> times = analysis.samples(ch).stream().filter(s -> !s.excluded()).map(Sample::postedAt).toList();
        return GoalMath.streak(times, ch.getStreakTarget(), now, ch.zone());
    }

    @Transactional
    public Goal set(Channel ch, int target, LocalDate deadline, Long userId) {
        Integer current = current(ch);
        if (current == null) throw new UserFacingException("goal.err_no_count");
        if (target <= current) throw new UserFacingException("goal.err_low", current);
        LocalDate today = LocalDate.now(ch.zone());
        if (!deadline.isAfter(today) || deadline.isAfter(today.plusYears(MAX_YEARS))) throw new UserFacingException("goal.err_date");
        Goal g = goals.findById(ch.getId()).orElseGet(Goal::new);
        g.setChannelId(ch.getId());
        g.setTarget(target);
        g.setDeadline(deadline);
        g.setStartCount(current);
        g.setStartedAt(Instant.now());
        g.setReachedAt(null);
        g.setSetBy(userId);
        return goals.save(g);
    }

    @Transactional
    public void clear(Channel ch) { goals.deleteById(ch.getId()); }

    public Channel setStreakTarget(Channel ch, int postsPerWeek) {
        if (postsPerWeek < 1 || postsPerWeek > MAX_STREAK_TARGET) throw new UserFacingException("goal.err_streak", MAX_STREAK_TARGET);
        ch.setStreakTarget(postsPerWeek);
        return channels.save(ch);
    }

    /** Marks the goal reached the first time the count gets there; returns it then, and only then. */
    @Transactional
    public Optional<Goal> reachedNow(Channel ch) {
        Optional<Goal> g = goal(ch);
        Integer current = current(ch);
        if (g.isEmpty() || g.get().getReachedAt() != null || current == null || current < g.get().getTarget()) return Optional.empty();
        if (LocalDate.now(ch.zone()).isAfter(g.get().getDeadline().plusDays(7))) return Optional.empty(); // long missed: no fanfare
        g.get().setReachedAt(Instant.now());
        return Optional.of(goals.save(g.get()));
    }

    Integer current(Channel ch) {
        if (ch.getSubscribers() != null) return ch.getSubscribers();
        return snapshots.findFirstByChannelIdOrderByTakenAtDesc(ch.getId()).map(SubscriberSnapshot::getMemberCount).orElse(null);
    }

    private List<GoalMath.Reading> readings(Channel ch, Instant now) {
        return snapshots.findAllByChannelIdAndTakenAtAfterOrderByTakenAtAsc(ch.getId(), now.minus(Duration.ofDays(GoalMath.PACE_DAYS + 1)))
                .stream().map(s -> new GoalMath.Reading(s.getTakenAt(), s.getMemberCount())).toList();
    }

    /**
     * Two goals to pick from: by the end of this month, what the current pace gets to (rounded down, so it starts on
     * track); in about three months, a third more than the pace (a stretch). Without a rising pace: 1% and 5% more.
     */
    private List<Suggestion> suggestions(Channel ch, Integer current, List<GoalMath.Reading> readings, Instant now) {
        if (current == null) return List.of();
        LocalDate today = now.atZone(ch.zone()).toLocalDate();
        Double perDay = GoalMath.pace(readings, current, now, null);
        boolean growing = perDay != null && perDay > 0;
        LocalDate soon = GoalMath.monthEnd(today, 14), later = GoalMath.monthEnd(today, 75);
        int near = growing ? GoalMath.roundDown(perDay * ChronoUnit.DAYS.between(today, soon)) : GoalMath.round(Math.max(10, current * 0.01));
        int far = growing ? GoalMath.round(perDay * ChronoUnit.DAYS.between(today, later) * 1.3) : GoalMath.round(Math.max(10, current * 0.05));
        return List.of(new Suggestion(current + near, soon, near), new Suggestion(current + Math.max(far, near + 1), later, Math.max(far, near + 1)));
    }

    private static final Pattern INPUT = Pattern.compile("^(\\+)?\\s*(\\d[\\d\\s.,]*?)\\s*([kкKК])?(?:\\s+(?:by|until|до|to|bis|hasta|até|-)?\\s*(\\S+))?\\s*$");

    /**
     * Reads "+500", "5000", "5k", "+1.5k 31.12.2026", "5000 2026-12-31", "+300 31.12": a "+" means that many more than
     * now. Without a date the deadline is the end of the month at least 30 days away.
     */
    public static Optional<Parsed> parse(String text, int current, LocalDate today) {
        Matcher m = INPUT.matcher(text.strip().toLowerCase(Locale.ROOT));
        if (!m.matches()) return Optional.empty();
        double n;
        try {
            String digits = m.group(2).replace(" ", "");
            // "1.5k" is a decimal; "5,000" and "5.000" group thousands
            n = m.group(3) != null ? Double.parseDouble(digits.replace(',', '.')) * 1000 : Double.parseDouble(digits.replaceAll("[.,]", ""));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (n <= 0 || n > 1e9) return Optional.empty();
        int target = (int) Math.round(m.group(1) != null ? current + n : n);
        LocalDate deadline = m.group(4) == null ? GoalMath.monthEnd(today, 30) : date(m.group(4), today);
        return deadline == null ? Optional.empty() : Optional.of(new Parsed(target, deadline));
    }

    private static LocalDate date(String s, LocalDate today) {
        try {
            if (s.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) return LocalDate.parse(s, java.time.format.DateTimeFormatter.ofPattern("yyyy-M-d"));
            Matcher m = Pattern.compile("(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{2,4}))?").matcher(s);
            if (!m.matches()) return null;
            int day = Integer.parseInt(m.group(1)), month = Integer.parseInt(m.group(2));
            int year = m.group(3) == null ? today.getYear() : Integer.parseInt(m.group(3));
            if (year < 100) year += 2000;
            LocalDate d = LocalDate.of(year, month, day);
            return m.group(3) == null && !d.isAfter(today) ? d.plusYears(1) : d;
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }
}
