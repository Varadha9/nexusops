package com.nexusops.hrms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkingDaysTest {

    static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    @Test
    void aWorkingWeekIsFiveDays() {
        assertThat(WorkingDays.count(d("2027-03-01"), d("2027-03-05"), Set.of())).isEqualTo(5); // Mon–Fri
    }

    @Test
    void fridayToMondayIsTwoDays() {
        assertThat(WorkingDays.count(d("2027-03-05"), d("2027-03-08"), Set.of())).isEqualTo(2);
    }

    @Test
    void aWeekendAloneIsNoWorkingDay() {
        assertThat(WorkingDays.count(d("2027-03-06"), d("2027-03-07"), Set.of())).isZero();
    }

    @Test
    void holidaysOnWeekdaysDontCountAndOnWeekendsChangeNothing() {
        assertThat(WorkingDays.count(d("2027-03-01"), d("2027-03-05"), Set.of(d("2027-03-03")))).isEqualTo(4);
        assertThat(WorkingDays.count(d("2027-03-01"), d("2027-03-07"), Set.of(d("2027-03-06")))).isEqualTo(5);
        assertThat(WorkingDays.count(d("2027-03-03"), d("2027-03-03"), Set.of(d("2027-03-03")))).isZero();
    }

    @Test
    void aSingleWorkingDayCountsOnce() {
        assertThat(WorkingDays.count(d("2027-03-03"), d("2027-03-03"), Set.of())).isEqualTo(1);
    }
}
