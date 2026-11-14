package io.siggi.healthchecker.maintenance;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SUNDAY;
import static java.time.DayOfWeek.THURSDAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecurrenceTest {
    private static List<LocalDate> dates(Recurrence r, LocalDate from, int count) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = r.next(from); d != null && out.size() < count; d = r.next(d.plusDays(1))) out.add(d);
        return out;
    }

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    @Test
    void daily() {
        Recurrence r = Recurrence.daily(d("2026-11-17"));
        assertEquals(List.of(d("2026-11-15"), d("2026-11-16"), d("2026-11-17")), dates(r, d("2026-11-15"), 10));
        assertEquals(1, r.minGapDays());
    }

    @Test
    void weeklyOnChosenDays() {
        Recurrence r = Recurrence.weekly(List.of(THURSDAY, MONDAY, MONDAY), null);
        assertEquals(List.of(MONDAY, THURSDAY), r.weekdays());
        assertEquals(List.of(d("2026-11-16"), d("2026-11-19"), d("2026-11-23"), d("2026-11-26")),
                dates(r, d("2026-11-14"), 4));
        assertEquals(3, r.minGapDays());
        assertEquals(7, Recurrence.weekly(List.of(SUNDAY), null).minGapDays());
    }

    @Test
    void weeklyGapWrapsAroundTheWeek() {
        assertEquals(1, Recurrence.weekly(List.of(MONDAY, SUNDAY), null).minGapDays());
    }

    @Test
    void monthlyFirstSunday() {
        Recurrence r = Recurrence.monthlyOnWeekday(1, SUNDAY, null);
        assertEquals(List.of(d("2026-12-06"), d("2027-01-03"), d("2027-02-07"), d("2027-03-07")),
                dates(r, d("2026-11-14"), 4));
        assertTrue(r.matches(d("2026-11-01")));
        assertFalse(r.matches(d("2026-11-08")));
        assertEquals(28, r.minGapDays());
    }

    @Test
    void monthlyLastAndFifthWeekday() {
        assertEquals(List.of(d("2026-11-27"), d("2026-12-25"), d("2027-01-29")),
                dates(Recurrence.monthlyOnWeekday(-1, FRIDAY, null), d("2026-11-14"), 3));
        // Only months with five Sundays.
        assertEquals(List.of(d("2026-11-29"), d("2027-01-31"), d("2027-05-30")),
                dates(Recurrence.monthlyOnWeekday(5, SUNDAY, null), d("2026-11-14"), 3));
    }

    @Test
    void monthlyOnDaySkipsShortMonths() {
        assertEquals(List.of(d("2026-12-31"), d("2027-01-31"), d("2027-03-31"), d("2027-05-31")),
                dates(Recurrence.monthlyOnDay(31, null), d("2026-11-14"), 4));
    }

    @Test
    void untilIsInclusive() {
        Recurrence r = Recurrence.monthlyOnDay(15, d("2027-01-15"));
        assertEquals(List.of(d("2026-11-15"), d("2026-12-15"), d("2027-01-15")), dates(r, d("2026-11-14"), 10));
        assertNull(r.next(d("2027-01-16")));
    }

    @Test
    void problems() {
        assertTrue(Recurrence.daily(null).problems().isEmpty());
        assertEquals(List.of("recurrence: choose at least one weekday"),
                Recurrence.weekly(List.of(), null).problems());
        assertEquals(1, Recurrence.monthlyOnDay(32, null).problems().size());
        assertEquals(1, Recurrence.monthlyOnDay(0, null).problems().size());
        assertEquals(1, Recurrence.monthlyOnWeekday(6, SUNDAY, null).problems().size());
        assertEquals(1, Recurrence.monthlyOnWeekday(1, null, null).problems().size());
        assertEquals(1, new Recurrence(Recurrence.Frequency.MONTHLY, List.of(), 3, 1, DayOfWeek.MONDAY, null)
                .problems().size());
        assertTrue(Recurrence.monthlyOnWeekday(-1, SUNDAY, null).problems().isEmpty());
    }
}
