package com.nexusops.hrms;

import com.nexusops.collaboration.SearchHit;
import com.nexusops.collaboration.SubjectRef;
import com.nexusops.collaboration.SubjectResolver;
import com.nexusops.directory.PartyBrief;
import com.nexusops.directory.PartyService;
import com.nexusops.hrms.domain.Employee;
import com.nexusops.hrms.domain.EmployeeRepository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Employees as collaboration subjects (D18): label "Name · number", archived once they have left. Reads the
 * repository and PartyService, not EmployeeService, which depends on Subjects indirectly (a cycle otherwise).
 */
@Component
class EmployeeSubjects implements SubjectResolver {

    static final String TYPE = "EMPLOYEE";

    private final EmployeeRepository employees;
    private final PartyService parties;
    private final HrQueries queries;
    private final HrClock clock;

    EmployeeSubjects(EmployeeRepository employees, PartyService parties, HrQueries queries, HrClock clock) {
        this.employees = employees;
        this.parties = parties;
        this.queries = queries;
        this.clock = clock;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String readPermission() {
        return HrPermissions.EMPLOYEE_READ;
    }

    @Override
    public Optional<SubjectRef> find(UUID id) {
        return employees.findById(id).map(e -> refs(List.of(e)).get(e.getId()));
    }

    @Override
    public Map<UUID, SubjectRef> findAll(Collection<UUID> ids) {
        return refs(employees.findAllById(ids));
    }

    @Override
    public List<SearchHit> search(String pattern, int limit) {
        List<UUID> ids = queries.searchEmployees(pattern, limit);
        Map<UUID, SubjectRef> refs = refs(employees.findAllById(ids));
        return ids.stream().filter(refs::containsKey).map(id -> {
            SubjectRef r = refs.get(id);
            return new SearchHit(TYPE, id, r.label(), null, r.archived());
        }).toList();
    }

    private Map<UUID, SubjectRef> refs(List<Employee> list) {
        Map<UUID, PartyBrief> people = parties.briefs(list.stream().map(Employee::getPersonId).collect(Collectors.toSet()));
        LocalDate today = clock.today();
        return list.stream().collect(Collectors.toMap(Employee::getId, e -> new SubjectRef(TYPE, e.getId(),
                (people.containsKey(e.getPersonId()) ? people.get(e.getPersonId()).name() : "Employee") + " · "
                        + e.getEmployeeNumber(),
                EmploymentStatus.of(e.getStartDate(), e.getEndDate(), today) == EmploymentStatus.LEFT)));
    }
}
