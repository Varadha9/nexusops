package com.nexusops.hrms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class EmploymentStatusTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 9);

    @Test
    void upcomingUntilTheStartDate() {
        assertThat(EmploymentStatus.of(TODAY.plusDays(1), null, TODAY)).isEqualTo(EmploymentStatus.UPCOMING);
        assertThat(EmploymentStatus.of(TODAY, null, TODAY)).isEqualTo(EmploymentStatus.ACTIVE);
    }

    @Test
    void activeThroughTheLastDayThenLeft() {
        assertThat(EmploymentStatus.of(TODAY.minusYears(1), TODAY, TODAY)).isEqualTo(EmploymentStatus.ACTIVE);
        assertThat(EmploymentStatus.of(TODAY.minusYears(1), TODAY.minusDays(1), TODAY)).isEqualTo(EmploymentStatus.LEFT);
        assertThat(EmploymentStatus.of(TODAY.minusYears(1), TODAY.plusDays(30), TODAY)).isEqualTo(EmploymentStatus.ACTIVE);
    }

    @Test
    void endedBeforeItStartedIsLeft() {
        assertThat(EmploymentStatus.of(TODAY.plusDays(5), TODAY.minusDays(1), TODAY)).isEqualTo(EmploymentStatus.LEFT);
    }
}
