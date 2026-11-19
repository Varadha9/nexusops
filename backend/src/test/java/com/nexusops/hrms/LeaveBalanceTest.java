package com.nexusops.hrms;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LeaveBalanceTest {

    @Test
    void entitlementIsTheAllowancePlusAdjustmentsLessTakenAndPending() {
        LeaveBalance b = LeaveBalance.of(20, new BigDecimal("-2.5"), 5, 3);
        assertThat(b.entitlement()).isEqualByComparingTo("17.5");
        assertThat(b.taken()).isEqualByComparingTo("5");
        assertThat(b.pending()).isEqualByComparingTo("3");
        assertThat(b.available()).isEqualByComparingTo("9.5");
    }

    @Test
    void unlimitedTypesHaveNoEntitlementOrAvailable() {
        LeaveBalance b = LeaveBalance.of(null, BigDecimal.ZERO, 4, 1);
        assertThat(b.entitlement()).isNull();
        assertThat(b.available()).isNull();
        assertThat(b.taken()).isEqualByComparingTo("4");
    }

    @Test
    void daysPrintWithoutTrailingZeros() {
        assertThat(LeaveBalance.days(new BigDecimal("2.50"))).isEqualTo("2.5");
        assertThat(LeaveBalance.days(new BigDecimal("3.0"))).isEqualTo("3");
        assertThat(LeaveBalance.days(new BigDecimal("0.0"))).isEqualTo("0");
        assertThat(LeaveBalance.days(new BigDecimal("-1.0"))).isEqualTo("-1");
    }
}
