package com.nexusops.hrms;

import com.nexusops.tenancy.TenantDirectory;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

/** "Today" for HR rules: the current date in the workspace's time zone (UTC when it has none or a bad one). */
@Component
public class HrClock {

    private final TenantDirectory tenants;
    private final Clock clock = Clock.systemUTC();

    HrClock(TenantDirectory tenants) {
        this.tenants = tenants;
    }

    public ZoneId zone() {
        try {
            String raw = tenants.currentSettings().timezone();
            return raw == null || raw.isBlank() ? ZoneOffset.UTC : ZoneId.of(raw.strip());
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(zone()));
    }
}
