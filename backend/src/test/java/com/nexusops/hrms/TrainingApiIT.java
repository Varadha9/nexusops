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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class TrainingApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID negotiation;
    UUID executive;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hrtrain"));
        owner = Api.login(mvc, ws);
        TestHr.enable(owner);
        negotiation = TestHr.skill(owner, "Negotiation");
        executive = TestHr.jobRole(owner, "Account executive", null, Map.of(negotiation, 4));
    }

    private UUID course(String title, UUID skill, int level) throws Exception {
        return Api.id(owner.post("/api/v1/hr/courses", "{\"title\":\"" + title + "\",\"durationHours\":3.5,"
                + "\"link\":\"https://learn.example/neg\",\"skills\":[{\"skillId\":\"" + skill + "\",\"level\":" + level
                + "}]}").andExpect(status().isCreated()));
    }

    private UUID employeeWithGap(String userEmail) throws Exception {
        String user = userEmail == null ? "" : ",\"userId\":\"" + OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select id from users where email = ?", UUID.class, userEmail) + "\"";
        UUID id = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Meera\",\"lastName\":\"Iyer\"},"
                + "\"employeeNumber\":\"E-1\",\"jobRoleId\":\"" + executive + "\",\"startDate\":\"2026-01-05\"" + user + "}"));
        owner.put("/api/v1/hr/employees/" + id + "/skills", "{\"skills\":[{\"skillId\":\"" + negotiation
                + "\",\"level\":2}],\"version\":0}").andExpect(status().isOk());
        return id;
    }

    @Test
    void coursesAreCreatedAndValidated() throws Exception {
        UUID id = course("Negotiation basics", negotiation, 3);
        owner.get("/api/v1/hr/courses/" + id).andExpect(jsonPath("$.durationHours").value(3.5))
                .andExpect(jsonPath("$.skills[0].name").value("Negotiation"))
                .andExpect(jsonPath("$.skills[0].level").value(3));
        String[][] cases = {
                {"{\"title\":\"\"}", "title"},
                {"{\"title\":\"T\",\"durationHours\":0}", "durationHours"},
                {"{\"title\":\"T\",\"durationHours\":0.04}", "durationHours"},
                {"{\"title\":\"T\",\"durationHours\":999.05}", "durationHours"},
                {"{\"title\":\"T\",\"durationHours\":1e999999999}", "durationHours"},
                {"{\"title\":\"T\",\"link\":\"ftp://x\"}", "link"},
                {"{\"title\":\"T\",\"skills\":[{\"skillId\":\"" + negotiation + "\",\"level\":9}]}", "skills[0].level"},
                {"{\"title\":\"T\",\"skills\":[{\"skillId\":\"" + UUID.randomUUID() + "\",\"level\":1}]}",
                        "skills[0].skillId"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/hr/courses", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        owner.put("/api/v1/hr/courses/" + id, "{\"title\":\"Negotiation basics\",\"skills\":[],\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.skills").isEmpty())
                .andExpect(jsonPath("$.link").doesNotExist());
    }

    @Test
    void theSkillGapRecommendsCoursesThatTeachEnough() throws Exception {
        UUID meera = employeeWithGap(null);
        UUID enough = course("Advanced negotiation", negotiation, 4);
        course("Negotiation basics", negotiation, 3);
        UUID archived = course("Old negotiation course", negotiation, 5);
        owner.post("/api/v1/hr/courses/" + archived + "/archive", "").andExpect(status().isOk());
        owner.get("/api/v1/hr/employees/" + meera + "/skill-gap")
                .andExpect(jsonPath("$.gaps[0].recommendedCourses[*].id").value(Matchers.contains(enough.toString())));
    }

    @Test
    void completingATrainingRaisesTheSkillAndClosesTheGap() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "Staff", "hr.self.service");
        Workspace staff = members.create(ws.tenantId(), Set.of(role));
        UUID meera = employeeWithGap(staff.email());
        UUID enough = course("Advanced negotiation", negotiation, 4);
        UUID id = Api.id(owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + enough + "\",\"employeeId\":\""
                + meera + "\",\"dueOn\":\"2099-01-31\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.overdue").value(false)));
        assertThat(mail.sentTo(staff.email())).anySatisfy(m -> assertThat(m.subject())
                .isEqualTo("Training assigned: Advanced negotiation"));
        owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + enough + "\",\"employeeId\":\"" + meera + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This course is already assigned to this employee."));
        owner.post("/api/v1/hr/training-assignments/" + id + "/complete", "{\"note\":\"Passed\",\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedOn").exists());
        owner.get("/api/v1/hr/employees/" + meera).andExpect(jsonPath("$.skills[0].level").value(4));
        owner.get("/api/v1/hr/employees/" + meera + "/skill-gap").andExpect(jsonPath("$.gaps").isEmpty());
        // once completed it can be assigned again
        owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + enough + "\",\"employeeId\":\"" + meera + "\"}")
                .andExpect(status().isCreated());
    }

    @Test
    void completionNeverLowersASkill() throws Exception {
        UUID meera = employeeWithGap(null);
        UUID basics = course("Negotiation basics", negotiation, 1);
        UUID id = Api.id(owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + basics
                + "\",\"employeeId\":\"" + meera + "\"}"));
        owner.post("/api/v1/hr/training-assignments/" + id + "/complete", "{\"version\":0}").andExpect(status().isOk());
        owner.get("/api/v1/hr/employees/" + meera).andExpect(jsonPath("$.skills[0].level").value(2));
    }

    @Test
    void overdueAssignmentsAreFlaggedAndListed() throws Exception {
        UUID meera = employeeWithGap(null);
        UUID enough = course("Advanced negotiation", negotiation, 4);
        UUID id = Api.id(owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + enough
                + "\",\"employeeId\":\"" + meera + "\",\"dueOn\":\"2099-01-31\"}"));
        OwnerJdbc.ownerAs(ws.tenantId()).update("update training_assignments set due_on = ? where id = ?",
                LocalDate.now(ZoneOffset.UTC).minusDays(3), id);
        owner.get("/api/v1/hr/training-assignments?overdue=true").andExpect(jsonPath("$.items[*].id").value(
                Matchers.contains(id.toString()))).andExpect(jsonPath("$.items[0].overdue").value(true));
        owner.get("/api/v1/hr/training-assignments?employeeId=" + meera + "&status=ASSIGNED")
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void assigningChecksCourseAndEmployee() throws Exception {
        UUID meera = employeeWithGap(null);
        UUID enough = course("Advanced negotiation", negotiation, 4);
        owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + UUID.randomUUID() + "\",\"employeeId\":\""
                + meera + "\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("courseId"));
        owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + enough + "\",\"employeeId\":\""
                + UUID.randomUUID() + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("employeeId"));
        owner.post("/api/v1/hr/courses/" + enough + "/archive", "").andExpect(status().isOk());
        owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + enough + "\",\"employeeId\":\"" + meera + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("This record is archived."));
    }

    @Test
    void dueDatesOutsideTheSupportedRangeAreFieldErrors() throws Exception {
        UUID meera = employeeWithGap(null);
        UUID enough = course("Advanced negotiation", negotiation, 4);
        for (String date : new String[] {"1899-12-31", "3000-01-01", "+6000000-01-01"}) {
            owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + enough + "\",\"employeeId\":\""
                    + meera + "\",\"dueOn\":\"" + date + "\"}").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("dueOn"))
                    .andExpect(jsonPath("$.errors[0].message").value("Enter a date from 1900 to 2999."));
        }
    }

    @Test
    void endingEmploymentCancelsOpenTraining() throws Exception {
        UUID meera = employeeWithGap(null);
        UUID enough = course("Advanced negotiation", negotiation, 4);
        UUID id = Api.id(owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + enough
                + "\",\"employeeId\":\"" + meera + "\"}"));
        long version = ((Number) Api.read(owner.get("/api/v1/hr/employees/" + meera), "$.version")).longValue();
        owner.post("/api/v1/hr/employees/" + meera + "/end", "{\"endDate\":\"" + LocalDate.now(ZoneOffset.UTC).minusDays(1)
                + "\",\"version\":" + version + "}").andExpect(status().isOk());
        owner.get("/api/v1/hr/training-assignments?employeeId=" + meera).andExpect(jsonPath("$.items[*].status")
                .value(Matchers.contains("CANCELLED")))
                .andExpect(jsonPath("$.items[0].id").value(id.toString()));
        owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + enough + "\",\"employeeId\":\"" + meera + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("This employee has left."));
    }

    @Test
    void readersSeeTrainingButOnlyTrainingManagersChangeIt() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "HR viewer", "hr.employee.read");
        Api viewer = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        viewer.get("/api/v1/hr/courses").andExpect(status().isOk());
        viewer.get("/api/v1/hr/training-assignments").andExpect(status().isOk());
        viewer.post("/api/v1/hr/courses", "{\"title\":\"X\"}").andExpect(status().isForbidden());
        viewer.post("/api/v1/hr/training-assignments", "{}").andExpect(status().isForbidden());
    }
}
