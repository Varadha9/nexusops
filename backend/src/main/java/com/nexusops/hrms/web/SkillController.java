package com.nexusops.hrms.web;

import com.nexusops.hrms.SkillService;
import com.nexusops.hrms.SkillView;
import com.nexusops.hrms.web.HrDtos.NameRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hr/skills")
class SkillController {

    private final SkillService skills;

    SkillController(SkillService skills) {
        this.skills = skills;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('hr.employee.read')")
    List<SkillView> list(@RequestParam(defaultValue = "false") boolean archived) {
        return skills.list(archived);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('hr.settings.manage')")
    SkillView create(@RequestBody NameRequest request) {
        return skills.create(request.name());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('hr.settings.manage')")
    SkillView update(@PathVariable UUID id, @RequestBody NameRequest request) {
        return skills.update(id, request.name(), request.version());
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('hr.settings.manage')")
    SkillView archive(@PathVariable UUID id) {
        return skills.archive(id);
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('hr.settings.manage')")
    SkillView restore(@PathVariable UUID id) {
        return skills.restore(id);
    }
}
