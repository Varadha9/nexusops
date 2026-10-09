package com.nexusops.hrms;

import com.nexusops.collaboration.SubjectKey;
import com.nexusops.collaboration.SubjectRelations;
import com.nexusops.hrms.domain.EmployeeRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** A person's timeline includes their employee record (D18). */
@Component
class EmployeeRelations implements SubjectRelations {

    private final EmployeeRepository employees;

    EmployeeRelations(EmployeeRepository employees) {
        this.employees = employees;
    }

    @Override
    public List<SubjectKey> related(String type, UUID id) {
        if (!"PARTY".equals(type)) {
            return List.of();
        }
        return employees.findByPersonId(id).map(e -> List.of(new SubjectKey(EmployeeSubjects.TYPE, e.getId())))
                .orElse(List.of());
    }
}
