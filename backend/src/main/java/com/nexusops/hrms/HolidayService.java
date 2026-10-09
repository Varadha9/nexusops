package com.nexusops.hrms;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.hrms.domain.Holiday;
import com.nexusops.hrms.domain.HolidayRepository;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.web.ApiProblem;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The workspace's public holidays (D12): they don't count as working days. */
@Service
public class HolidayService {

    static final String DATE_TAKEN = "There is already a holiday on this date.";
    private static final String LOCK = "hr-holidays";

    private final HolidayRepository holidays;
    private final HrClock clock;
    private final TenantLocks locks;
    private final AuditService audit;

    HolidayService(HolidayRepository holidays, HrClock clock, TenantLocks locks, AuditService audit) {
        this.holidays = holidays;
        this.clock = clock;
        this.locks = locks;
        this.audit = audit;
    }

    /** All holidays of a calendar year (the current one when {@code year} is null). */
    @Transactional(readOnly = true)
    public List<HolidayView> list(Integer year) {
        TenantContext.requireTenantId();
        int y = year == null ? clock.today().getYear() : year;
        if (y < 1 || y > 9999) {
            throw ApiProblem.badRequestField("year", "Enter a year.");
        }
        return holidays.findByDateBetweenOrderByDateAsc(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31)).stream()
                .map(HolidayService::view).toList();
    }

    @Transactional
    public HolidayView add(LocalDate date, String rawName) {
        TenantContext.requireTenantId();
        if (date == null) {
            throw ApiProblem.badRequestField("date", "Enter a date.");
        }
        String name = HrRecords.collapse(Text.required(rawName, 80, "name"));
        locks.lock(LOCK);
        if (holidays.existsByDate(date)) {
            throw ApiProblem.conflictField("date", DATE_TAKEN);
        }
        Holiday holiday = holidays.saveAndFlush(new Holiday(Ids.newId(), date, name));
        audit.record(AuditEntry.of("HolidayAdded", "Holiday", holiday.getId())
                .withAfter(Map.of("date", date.toString(), "name", name)));
        return view(holiday);
    }

    @Transactional
    public void remove(UUID id) {
        TenantContext.requireTenantId();
        Holiday holiday = holidays.findById(id).orElseThrow(() -> ApiProblem.notFound(HrRecords.NOT_FOUND));
        holidays.delete(holiday);
        holidays.flush();
        audit.record(AuditEntry.of("HolidayRemoved", "Holiday", id)
                .withBefore(Map.of("date", holiday.getDate().toString(), "name", holiday.getName())));
    }

    Set<LocalDate> between(LocalDate from, LocalDate to) {
        return holidays.findByDateBetweenOrderByDateAsc(from, to).stream().map(Holiday::getDate)
                .collect(Collectors.toSet());
    }

    private static HolidayView view(Holiday h) {
        return new HolidayView(h.getId(), h.getDate(), h.getName());
    }
}
