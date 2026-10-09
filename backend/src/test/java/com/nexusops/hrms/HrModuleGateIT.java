package com.nexusops.hrms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHr;
import com.nexusops.support.TestTenants;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Disabling HRMS removes every HRMS permission, so every HRMS route answers 403. */
@AutoConfigureMockMvc
class HrModuleGateIT extends IntegrationTestSupport {

    private static final Pattern HR_AUTHORITY = Pattern.compile(
            "^(hasAuthority\\('hr\\.[a-z.]+'\\)|hasAnyAuthority\\('hr\\.[a-z.]+'(,'hr\\.[a-z.]+')*\\))$");

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    void everyHrHandlerRequiresAnHrPermission() {
        List<String> violations = new ArrayList<>();
        Set<String> checked = new HashSet<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            for (String path : info.getPatternValues()) {
                if (!path.startsWith("/api/v1/hr/")) {
                    continue;
                }
                var preAuthorize = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
                String expression = preAuthorize == null ? "" : preAuthorize.value().replace(" ", "");
                checked.add(info.getMethodsCondition() + " " + path);
                if (!HR_AUTHORITY.matcher(expression).matches()) {
                    violations.add(info.getMethodsCondition() + " " + path + " -> " + expression);
                }
            }
        });
        assertThat(checked).as("HRMS routes were found").hasSizeGreaterThanOrEqualTo(45);
        assertThat(violations).isEmpty();
    }

    @Test
    void everyHrRouteIsForbiddenWhileTheModuleIsOff() throws Exception {
        Api owner = Api.login(mvc, TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hrgate")));
        TestHr.enable(owner);
        owner.put("/api/v1/tenant/modules/HRMS", "{\"enabled\":false}").andExpect(status().isOk());
        UUID any = UUID.randomUUID();
        owner.get("/api/v1/hr/employees").andExpect(status().isForbidden());
        owner.get("/api/v1/hr/employees/" + any).andExpect(status().isForbidden());
        owner.post("/api/v1/hr/employees", "{}").andExpect(status().isForbidden());
        owner.post("/api/v1/hr/employees/" + any + "/end", "{}").andExpect(status().isForbidden());
        owner.get("/api/v1/hr/employees/" + any + "/skill-gap").andExpect(status().isForbidden());
        owner.get("/api/v1/hr/departments").andExpect(status().isForbidden());
        owner.get("/api/v1/hr/job-roles").andExpect(status().isForbidden());
        owner.get("/api/v1/hr/skills").andExpect(status().isForbidden());
        owner.get("/api/v1/hr/leave-types").andExpect(status().isForbidden());
        owner.get("/api/v1/hr/holidays").andExpect(status().isForbidden());
        owner.get("/api/v1/hr/leave-requests").andExpect(status().isForbidden());
        owner.post("/api/v1/hr/leave-requests/" + any + "/approve", "{}").andExpect(status().isForbidden());
        owner.get("/api/v1/hr/leave/away").andExpect(status().isForbidden());
        owner.get("/api/v1/hr/courses").andExpect(status().isForbidden());
        owner.get("/api/v1/hr/training-assignments").andExpect(status().isForbidden());
        owner.get("/api/v1/hr/policies").andExpect(status().isForbidden());
        owner.get("/api/v1/hr/me").andExpect(status().isForbidden());
        owner.post("/api/v1/hr/me/leave-requests", "{}").andExpect(status().isForbidden());
        owner.get("/api/v1/hr/onboarding-templates").andExpect(status().isForbidden());
        owner.get("/api/v1/hr/dashboard").andExpect(status().isForbidden());
        TestHr.enable(owner);
        owner.get("/api/v1/hr/employees").andExpect(status().isOk());
    }
}
