package io.siggi.healthchecker.maintenance;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * A recurrence rule for a maintenance series, on dates in the configured timezone. Which dates it yields
 * doesn't depend on the time of day; each occurrence keeps the series' local start time (see
 * {@link MaintenanceStore}).
 *
 * <ul>
 *   <li>{@link Frequency#DAILY}: every day.</li>
 *   <li>{@link Frequency#WEEKLY}: on the chosen {@code weekdays}.</li>
 *   <li>{@link Frequency#MONTHLY}: on {@code dayOfMonth} (1–31; months without that day are skipped), or on
 *       the {@code week}-th {@code weekday} of the month (1–5, or -1 for the last; months without a 5th one
 *       are skipped), e.g. "first Sunday" is {@code week 1, SUNDAY}.</li>
 * </ul>
 * {@code until} (optional, inclusive) is the last date an occurrence may fall on.
 *
 * <p>The record itself accepts any values (so it can carry form input); {@link #problems()} lists what's
 * wrong, and the other methods assume there's nothing.
 *
 * @param frequency  how often
 * @param weekdays   weekly: the days, in order Monday first; empty otherwise
 * @param dayOfMonth monthly by day of month: 1–31; otherwise 0
 * @param week       monthly by weekday: 1–5 or -1 (last); otherwise 0
 * @param weekday    monthly by weekday: the day; otherwise {@code null}
 * @param until      last possible date, or {@code null} for no end
 */
public record Recurrence(Frequency frequency, List<DayOfWeek> weekdays, int dayOfMonth, int week, DayOfWeek weekday,
                         LocalDate until) {

    public enum Frequency {
        DAILY, WEEKLY, MONTHLY
    }

    public Recurrence {
        Objects.requireNonNull(frequency, "frequency");
        EnumSet<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (weekdays != null) weekdays.stream().filter(Objects::nonNull).forEach(days::add);
        weekdays = List.copyOf(days);
    }

    public static Recurrence daily(LocalDate until) {
        return new Recurrence(Frequency.DAILY, List.of(), 0, 0, null, until);
    }

    public static Recurrence weekly(List<DayOfWeek> weekdays, LocalDate until) {
        return new Recurrence(Frequency.WEEKLY, weekdays, 0, 0, null, until);
    }

    public static Recurrence monthlyOnDay(int dayOfMonth, LocalDate until) {
        return new Recurrence(Frequency.MONTHLY, List.of(), dayOfMonth, 0, null, until);
    }

    /** E.g. {@code monthlyOnWeekday(1, SUNDAY, …)} for "first Sunday", {@code -1} for the last one. */
    public static Recurrence monthlyOnWeekday(int week, DayOfWeek weekday, LocalDate until) {
        return new Recurrence(Frequency.MONTHLY, List.of(), 0, week, weekday, until);
    }

    /** What's wrong with the rule, as {@code "recurrence: …"} messages; empty when it's usable. */
    public List<String> problems() {
        List<String> out = new ArrayList<>();
        switch (frequency) {
            case DAILY -> {
                if (!weekdays.isEmpty() || dayOfMonth != 0 || week != 0 || weekday != null) {
                    out.add("recurrence: a daily rule takes no days");
                }
            }
            case WEEKLY -> {
                if (weekdays.isEmpty()) out.add("recurrence: choose at least one weekday");
                if (dayOfMonth != 0 || week != 0 || weekday != null) {
                    out.add("recurrence: a weekly rule takes only weekdays");
                }
            }
            case MONTHLY -> {
                if (!weekdays.isEmpty()) out.add("recurrence: a monthly rule takes no weekday list");
                boolean byDay = dayOfMonth != 0;
                boolean byWeekday = week != 0 || weekday != null;
                if (byDay == byWeekday) {
                    out.add("recurrence: choose either a day of the month or a weekday of the month");
                } else if (byDay && (dayOfMonth < 1 || dayOfMonth > 31)) {
                    out.add("recurrence: the day of the month must be 1–31");
                } else if (byWeekday && (weekday == null || week < -1 || week == 0 || week > 5)) {
                    out.add("recurrence: choose the weekday and which one (1st–5th or last)");
                }
            }
        }
        return out;
    }

    /**
     * The first date on or after {@code from} that the rule yields, or {@code null} if there's none
     * ({@code until} passed).
     */
    public LocalDate next(LocalDate from) {
        LocalDate date = switch (frequency) {
            case DAILY -> from;
            case WEEKLY -> {
                if (weekdays.isEmpty()) yield null;
                LocalDate d = from;
                while (!weekdays.contains(d.getDayOfWeek())) d = d.plusDays(1);
                yield d;
            }
            case MONTHLY -> {
                YearMonth month = YearMonth.from(from);
                LocalDate found = null;
                // Every day of the month and every 5th weekday occurs within a few months.
                for (int i = 0; i < 24 && found == null; i++, month = month.plusMonths(1)) {
                    LocalDate candidate = inMonth(month);
                    if (candidate != null && !candidate.isBefore(from)) found = candidate;
                }
                yield found;
            }
        };
        if (date == null || (until != null && date.isAfter(until))) return null;
        return date;
    }

    /** Whether {@code date} is one the rule yields (ignoring where a series starts). */
    public boolean matches(LocalDate date) {
        return date.equals(next(date));
    }

    private LocalDate inMonth(YearMonth month) {
        if (dayOfMonth != 0) return month.isValidDay(dayOfMonth) ? month.atDay(dayOfMonth) : null;
        if (week == -1) return month.atEndOfMonth().with(TemporalAdjusters.previousOrSame(weekday));
        LocalDate d = month.atDay(1).with(TemporalAdjusters.nextOrSame(weekday)).plusWeeks(week - 1L);
        return YearMonth.from(d).equals(month) ? d : null;
    }

    /** The fewest days between two consecutive dates, the longest an occurrence may be without overlapping. */
    public int minGapDays() {
        return switch (frequency) {
            case DAILY -> 1;
            case WEEKLY -> {
                int min = 7;
                for (int i = 1; i < weekdays.size(); i++) {
                    min = Math.min(min, weekdays.get(i).getValue() - weekdays.get(i - 1).getValue());
                }
                if (weekdays.size() > 1) {
                    min = Math.min(min, weekdays.get(0).getValue() + 7 - weekdays.get(weekdays.size() - 1).getValue());
                }
                yield min;
            }
            case MONTHLY -> 28;
        };
    }
}
