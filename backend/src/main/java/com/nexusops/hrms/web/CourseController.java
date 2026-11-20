package com.nexusops.hrms.web;

import com.nexusops.hrms.CourseService;
import com.nexusops.hrms.CourseView;
import com.nexusops.hrms.web.HrDtos.CourseRequest;
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
@RequestMapping("/api/v1/hr/courses")
class CourseController {

    private final CourseService courses;

    CourseController(CourseService courses) {
        this.courses = courses;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('hr.employee.read')")
    List<CourseView> list(@RequestParam(defaultValue = "false") boolean archived) {
        return courses.list(archived);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('hr.employee.read')")
    CourseView get(@PathVariable UUID id) {
        return courses.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('hr.training.manage')")
    CourseView create(@RequestBody CourseRequest request) {
        return courses.create(request.command());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('hr.training.manage')")
    CourseView update(@PathVariable UUID id, @RequestBody CourseRequest request) {
        return courses.update(id, request.command(), request.version());
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('hr.training.manage')")
    CourseView archive(@PathVariable UUID id) {
        return courses.archive(id);
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('hr.training.manage')")
    CourseView restore(@PathVariable UUID id) {
        return courses.restore(id);
    }
}
