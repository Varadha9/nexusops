package com.nexusops.hrms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHr;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class OnboardingApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hronb"));
        owner = Api.login(mvc, ws);
        TestHr.enable(owner);
    }

    private UUID standard() throws Exception {
        List<String> ids = Api.read(owner.get("/api/v1/hr/onboarding-templates"),
                "$[?(@.name == 'Standard onboarding')].id");
        return UUID.fromString(ids.getFirst());
    }

    private UUID userId(String email) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select id from users where email = ?", UUID.class, email);
    }

    @Test
    void everyWorkspaceStartsWithAStandardTemplate() throws Exception {
        owner.get("/api/v1/hr/onboarding-templates")
                .andExpect(jsonPath("$[0].name").value("Standard onboarding"))
                .andExpect(jsonPath("$[0].items.length()").value(5))
                .andExpect(jsonPath("$[0].items[0].owner").value("HR"))
                .andExpect(jsonPath("$[0].items[4].dueOffsetDays").value(30));
    }

    @Test
    void hiringWithATemplateCreatesTasksForHrTheManagerAndTheNewHire() throws Exception {
        UUID managerRole = TestRoles.create(mvc, owner.session(), "Manager", "collaboration.task.read");
        Workspace managerLogin = members.create(ws.tenantId(), Set.of(managerRole));
        Workspace hireLogin = members.create(ws.tenantId(), Set.of(managerRole));
        UUID manager = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Ravi\",\"lastName\":"
                + "\"Kumar\"},\"employeeNumber\":\"M-1\",\"startDate\":\"2026-01-05\",\"userId\":\""
                + userId(managerLogin.email()) + "\"}"));
        UUID id = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Meera\",\"lastName\":"
                + "\"Iyer\"},\"employeeNumber\":\"E-1\",\"startDate\":\"2026-11-02\",\"managerId\":\"" + manager
                + "\",\"userId\":\"" + userId(hireLogin.email()) + "\",\"onboardingTemplateId\":\"" + standard() + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.onboarding.templateName").value("Standard onboarding"))
                .andExpect(jsonPath("$.onboarding.done").value(0))
                .andExpect(jsonPath("$.onboarding.total").value(5)));
        owner.get("/api/v1/tasks?subjectType=EMPLOYEE&subjectId=" + id + "&size=10")
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items[?(@.title == 'Prepare laptop and accounts')].assignee.id")
                        .value(Matchers.contains(userId(ws.email()).toString())))
                .andExpect(jsonPath("$.items[?(@.title == 'Prepare laptop and accounts')].dueOn")
                        .value(Matchers.contains("2026-11-02")))
                .andExpect(jsonPath("$.items[?(@.title == '30-day check-in')].assignee.id")
                        .value(Matchers.contains(userId(managerLogin.email()).toString())))
                .andExpect(jsonPath("$.items[?(@.title == '30-day check-in')].dueOn")
                        .value(Matchers.contains("2026-12-02")))
                .andExpect(jsonPath("$.items[?(@.title == 'Read the employee handbook')].assignee.id")
                        .value(Matchers.contains(userId(hireLogin.email()).toString())));
        assertThat(mail.sentTo(managerLogin.email())).isNotEmpty();
        assertThat(mail.sentTo(hireLogin.email())).isNotEmpty();
        // finishing a task moves the progress
        String taskId = Api.read(owner.get("/api/v1/tasks?subjectType=EMPLOYEE&subjectId=" + id + "&size=10"),
                "$.items[?(@.title == 'Prepare laptop and accounts')].id").toString().replaceAll("[\\[\\]\"]", "");
        owner.post("/api/v1/tasks/" + taskId + "/status", "{\"status\":\"DONE\"}").andExpect(status().isOk());
        owner.get("/api/v1/hr/employees/" + id).andExpect(jsonPath("$.onboarding.done").value(1));
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = 'OnboardingStarted'", Long.class)).isEqualTo(1);
    }

    @Test
    void withoutLinkedAccountsTheTasksGoToWhoeverStartedOnboarding() throws Exception {
        UUID id = TestHr.hire(owner, "Arun", "Shah", "E-2");
        owner.get("/api/v1/hr/employees/" + id).andExpect(jsonPath("$.onboarding").doesNotExist());
        owner.post("/api/v1/hr/employees/" + id + "/onboarding", "{\"templateId\":\"" + standard() + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.onboarding.total").value(5));
        owner.get("/api/v1/tasks?subjectType=EMPLOYEE&subjectId=" + id + "&size=10")
                .andExpect(jsonPath("$.items[*].assignee.id").value(Matchers.everyItem(
                        Matchers.is(userId(ws.email()).toString()))));
        owner.post("/api/v1/hr/employees/" + id + "/onboarding", "{\"templateId\":\"" + standard() + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Onboarding has already started for this employee."));
    }

    @Test
    void startingOnboardingChecksTheTemplate() throws Exception {
        UUID id = TestHr.hire(owner, "Arun", "Shah", "E-2");
        owner.post("/api/v1/hr/employees/" + id + "/onboarding", "{\"templateId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("templateId"));
        owner.post("/api/v1/hr/employees/" + id + "/onboarding", "{}").andExpect(status().isBadRequest());
        UUID standard = standard();
        owner.post("/api/v1/hr/onboarding-templates/" + standard + "/archive", "").andExpect(status().isOk());
        owner.post("/api/v1/hr/employees/" + id + "/onboarding", "{\"templateId\":\"" + standard + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("This record is archived."));
        owner.post("/api/v1/hr/employees/" + UUID.randomUUID() + "/onboarding", "{\"templateId\":\"" + standard + "\"}")
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnknownTemplateOnTheHireIsABodyErrorAndCreatesNothing() throws Exception {
        String body = "{\"person\":{\"firstName\":\"Meera\",\"lastName\":\"Iyer\"},\"employeeNumber\":\"E-9\","
                + "\"startDate\":\"2026-11-02\",\"onboardingTemplateId\":\"" + UUID.randomUUID() + "\"}";
        owner.post("/api/v1/hr/employees", body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("onboardingTemplateId"));
        owner.get("/api/v1/hr/employees").andExpect(jsonPath("$.total").value(0));
        // a taken employee number would be a 409, but the body error comes first
        TestHr.hire(owner, "Arun", "Shah", "E-9");
        owner.post("/api/v1/hr/employees", body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("onboardingTemplateId"));
        owner.get("/api/v1/hr/employees").andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void tasksForDisabledAccountsGoToWhoeverStartedOnboarding() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "Manager", "collaboration.task.read");
        Workspace managerLogin = members.create(ws.tenantId(), Set.of(role));
        Workspace hireLogin = members.create(ws.tenantId(), Set.of(role));
        UUID manager = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Ravi\",\"lastName\":"
                + "\"Kumar\"},\"employeeNumber\":\"M-1\",\"startDate\":\"2026-01-05\",\"userId\":\""
                + userId(managerLogin.email()) + "\"}"));
        UUID id = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Meera\",\"lastName\":"
                + "\"Iyer\"},\"employeeNumber\":\"E-1\",\"startDate\":\"2026-11-02\",\"managerId\":\"" + manager
                + "\",\"userId\":\"" + userId(hireLogin.email()) + "\"}"));
        OwnerJdbc.ownerAs(ws.tenantId()).update("update users set status = 'DISABLED' where email in (?, ?)",
                managerLogin.email(), hireLogin.email());
        owner.post("/api/v1/hr/employees/" + id + "/onboarding", "{\"templateId\":\"" + standard() + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.onboarding.total").value(5));
        owner.get("/api/v1/tasks?subjectType=EMPLOYEE&subjectId=" + id + "&size=10")
                .andExpect(jsonPath("$.items[*].assignee.id").value(Matchers.everyItem(
                        Matchers.is(userId(ws.email()).toString()))));
    }

    @Test
    void cancelledTasksNoLongerCountTowardsProgress() throws Exception {
        UUID id = TestHr.hire(owner, "Arun", "Shah", "E-2");
        owner.post("/api/v1/hr/employees/" + id + "/onboarding", "{\"templateId\":\"" + standard() + "\"}")
                .andExpect(status().isOk());
        String taskId = Api.read(owner.get("/api/v1/tasks?subjectType=EMPLOYEE&subjectId=" + id + "&size=10"),
                "$.items[?(@.title == '30-day check-in')].id").toString().replaceAll("[\\[\\]\"]", "");
        owner.post("/api/v1/tasks/" + taskId + "/status", "{\"status\":\"CANCELLED\"}").andExpect(status().isOk());
        owner.get("/api/v1/hr/employees/" + id).andExpect(jsonPath("$.onboarding.total").value(4))
                .andExpect(jsonPath("$.onboarding.done").value(0));
    }

    @Test
    void templatesAreEditedAsAWhole() throws Exception {
        UUID id = Api.id(owner.post("/api/v1/hr/onboarding-templates", "{\"name\":\"Sales onboarding\",\"items\":["
                + "{\"title\":\"Shadow a sales call\",\"owner\":\"MANAGER\",\"dueOffsetDays\":5},"
                + "{\"title\":\"Set up CRM access\",\"description\":\"Ask IT\",\"owner\":\"HR\",\"dueOffsetDays\":0}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[*].title").value(Matchers.contains("Shadow a sales call",
                        "Set up CRM access"))));
        owner.put("/api/v1/hr/onboarding-templates/" + id, "{\"name\":\"Sales onboarding\",\"items\":["
                + "{\"title\":\"Set up CRM access\",\"owner\":\"HR\",\"dueOffsetDays\":1}],\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].dueOffsetDays").value(1));
        String[][] cases = {
                {"{\"name\":\"\",\"items\":[{\"title\":\"T\",\"owner\":\"HR\",\"dueOffsetDays\":0}]}", "name"},
                {"{\"name\":\"X\",\"items\":[]}", "items"},
                {"{\"name\":\"X\",\"items\":[{\"title\":\"\",\"owner\":\"HR\",\"dueOffsetDays\":0}]}", "items[0].title"},
                {"{\"name\":\"X\",\"items\":[{\"title\":\"T\",\"dueOffsetDays\":0}]}", "items[0].owner"},
                {"{\"name\":\"X\",\"items\":[{\"title\":\"T\",\"owner\":\"HR\",\"dueOffsetDays\":366}]}",
                        "items[0].dueOffsetDays"},
                {"{\"name\":\"X\",\"items\":[{\"title\":\"T\",\"owner\":\"HR\"}]}", "items[0].dueOffsetDays"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/hr/onboarding-templates", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        owner.post("/api/v1/hr/onboarding-templates", "{\"name\":\"SALES ONBOARDING\",\"items\":[{\"title\":\"T\","
                + "\"owner\":\"HR\",\"dueOffsetDays\":0}]}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void onlySettingsManagersEditTemplates() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "HR viewer", "hr.employee.read");
        Api viewer = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        viewer.get("/api/v1/hr/onboarding-templates").andExpect(status().isOk());
        viewer.post("/api/v1/hr/onboarding-templates", "{}").andExpect(status().isForbidden());
    }
}
