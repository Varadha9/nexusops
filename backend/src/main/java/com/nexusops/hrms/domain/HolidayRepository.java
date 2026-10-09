package com.nexusops.hrms.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HolidayRepository extends JpaRepository<Holiday, UUID> {

    List<Holiday> findByDateBetweenOrderByDateAsc(LocalDate from, LocalDate to);

    boolean existsByDate(LocalDate date);
}
