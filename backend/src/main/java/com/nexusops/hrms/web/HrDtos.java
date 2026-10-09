package com.nexusops.hrms.web;

import com.nexusops.hrms.DepartmentCommand;
import com.nexusops.hrms.EmployeeCommand;
import com.nexusops.hrms.EmployeeSkillCommand;
import com.nexusops.hrms.EmployeeUpdate;
import com.nexusops.hrms.EmploymentType;
import com.nexusops.hrms.JobRoleCommand;
import com.nexusops.hrms.NewPerson;
import com.nexusops.hrms.RequiredSkill;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request bodies of the HRMS API; later tasks add theirs here. */
final class HrDtos {

    private HrDtos() {}

    record NameRequest(String name, Long version) {}

    record DepartmentRequest(String name, String description, Long version) {
        DepartmentCommand command() {
            return new DepartmentCommand(name, description);
        }
    }

    record JobRoleRequest(String title, UUID departmentId, String description, List<RequiredSkill> requiredSkills,
            Long version) {
        JobRoleCommand command() {
            return new JobRoleCommand(title, departmentId, description, requiredSkills);
        }
    }

    record VersionRequest(Long version) {}

    record EmployeeRequest(UUID personId, NewPerson person, String employeeNumber, UUID departmentId, UUID jobRoleId,
            UUID managerId, EmploymentType employmentType, LocalDate startDate, UUID userId, UUID onboardingTemplateId,
            Long version) {
        EmployeeCommand hire() {
            return new EmployeeCommand(personId, person, employeeNumber, departmentId, jobRoleId, managerId,
                    employmentType, startDate, userId, onboardingTemplateId);
        }

        EmployeeUpdate update() {
            return new EmployeeUpdate(employeeNumber, departmentId, jobRoleId, managerId, employmentType, startDate,
                    userId);
        }
    }

    record EndRequest(LocalDate endDate, String reason, Long version) {}

    record SkillsRequest(List<EmployeeSkillCommand> skills, Long version) {}
}
