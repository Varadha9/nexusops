package com.nexusops.hrms.web;

import com.nexusops.hrms.DepartmentCommand;
import com.nexusops.hrms.JobRoleCommand;
import com.nexusops.hrms.RequiredSkill;
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
}
