package com.nexusops.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Enables the HRMS module and creates its reference records for tests. */
public final class TestHr {

    private TestHr() {}

    public static void enable(Api owner) throws Exception {
        owner.put("/api/v1/tenant/modules/HRMS", "{\"enabled\":true}").andExpect(status().isOk());
    }

    public static UUID skill(Api owner, String name) throws Exception {
        return Api.id(owner.post("/api/v1/hr/skills", "{\"name\":\"" + name + "\"}").andExpect(status().isCreated()));
    }

    public static UUID department(Api owner, String name) throws Exception {
        return Api.id(owner.post("/api/v1/hr/departments", "{\"name\":\"" + name + "\"}")
                .andExpect(status().isCreated()));
    }

    public static UUID jobRole(Api owner, String title, UUID departmentId, Map<UUID, Integer> required)
            throws Exception {
        String skills = required.entrySet().stream()
                .map(e -> "{\"skillId\":\"" + e.getKey() + "\",\"minimumLevel\":" + e.getValue() + "}")
                .collect(Collectors.joining(",", "[", "]"));
        String department = departmentId == null ? "null" : "\"" + departmentId + "\"";
        return Api.id(owner.post("/api/v1/hr/job-roles", "{\"title\":\"" + title + "\",\"departmentId\":" + department
                + ",\"requiredSkills\":" + skills + "}").andExpect(status().isCreated()));
    }
}
