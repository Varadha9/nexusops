package com.nexusops.hrms;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHr;
import com.nexusops.support.TestTenants;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class JobRoleApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    Api owner;
    UUID sales;
    UUID negotiation;
    UUID excel;

    @BeforeEach
    void workspace() throws Exception {
        owner = Api.login(mvc, TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hrrole")));
        TestHr.enable(owner);
        sales = TestHr.department(owner, "Sales");
        negotiation = TestHr.skill(owner, "Negotiation");
        excel = TestHr.skill(owner, "Excel");
    }

    @Test
    void aJobRoleNamesTheSkillsItNeeds() throws Exception {
        UUID id = Api.id(owner.post("/api/v1/hr/job-roles", "{\"title\":\"Account executive\",\"departmentId\":\""
                + sales + "\",\"description\":\"Sells\",\"requiredSkills\":[{\"skillId\":\"" + negotiation
                + "\",\"minimumLevel\":4},{\"skillId\":\"" + excel + "\",\"minimumLevel\":2}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.department.name").value("Sales"))
                .andExpect(jsonPath("$.requiredSkills[*].name").value(Matchers.contains("Excel", "Negotiation")))
                .andExpect(jsonPath("$.requiredSkills[1].minimumLevel").value(4)));
        owner.put("/api/v1/hr/job-roles/" + id, "{\"title\":\"Account executive\",\"requiredSkills\":[{\"skillId\":\""
                + excel + "\",\"minimumLevel\":3}],\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.department").doesNotExist())
                .andExpect(jsonPath("$.requiredSkills.length()").value(1))
                .andExpect(jsonPath("$.requiredSkills[0].minimumLevel").value(3));
        owner.get("/api/v1/hr/job-roles/" + id).andExpect(jsonPath("$.version").value(1));
        UUID other = TestHr.jobRole(owner, "Sales manager", sales, Map.of(negotiation, 5));
        owner.get("/api/v1/hr/job-roles?departmentId=" + sales).andExpect(jsonPath("$[*].id").value(
                Matchers.contains(other.toString())));
        owner.post("/api/v1/hr/job-roles/" + id + "/archive", "").andExpect(status().isOk());
        owner.get("/api/v1/hr/job-roles").andExpect(jsonPath("$[*].title").value(Matchers.contains("Sales manager")));
    }

    @Test
    void requiredSkillsAreValidated() throws Exception {
        String[][] cases = {
                {"{\"title\":\"\"}", "title"},
                {"{\"title\":\"R\",\"departmentId\":\"" + UUID.randomUUID() + "\"}", "departmentId"},
                {"{\"title\":\"R\",\"requiredSkills\":[{\"skillId\":\"" + UUID.randomUUID()
                        + "\",\"minimumLevel\":2}]}", "requiredSkills[0].skillId"},
                {"{\"title\":\"R\",\"requiredSkills\":[{\"skillId\":\"" + excel + "\",\"minimumLevel\":6}]}",
                        "requiredSkills[0].minimumLevel"},
                {"{\"title\":\"R\",\"requiredSkills\":[{\"skillId\":\"" + excel + "\"}]}",
                        "requiredSkills[0].minimumLevel"},
                {"{\"title\":\"R\",\"requiredSkills\":[{\"skillId\":\"" + excel + "\",\"minimumLevel\":2},"
                        + "{\"skillId\":\"" + excel + "\",\"minimumLevel\":3}]}", "requiredSkills[1].skillId"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/hr/job-roles", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        owner.post("/api/v1/hr/job-roles", "{\"title\":\"Account executive\"}").andExpect(status().isCreated());
        owner.post("/api/v1/hr/job-roles", "{\"title\":\"ACCOUNT EXECUTIVE\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("title"));
    }

    @Test
    void archivedSkillsAndDepartmentsCantBeNewlyChosen() throws Exception {
        UUID id = TestHr.jobRole(owner, "Analyst", sales, Map.of(excel, 2));
        owner.post("/api/v1/hr/skills/" + excel + "/archive", "").andExpect(status().isOk());
        owner.post("/api/v1/hr/departments/" + sales + "/archive", "").andExpect(status().isOk());
        // unchanged references are kept
        owner.put("/api/v1/hr/job-roles/" + id, "{\"title\":\"Senior analyst\",\"departmentId\":\"" + sales
                + "\",\"requiredSkills\":[{\"skillId\":\"" + excel + "\",\"minimumLevel\":3}],\"version\":0}")
                .andExpect(status().isOk());
        // new ones are refused
        owner.post("/api/v1/hr/job-roles", "{\"title\":\"Clerk\",\"requiredSkills\":[{\"skillId\":\"" + excel
                + "\",\"minimumLevel\":1}]}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This record is archived."));
        owner.post("/api/v1/hr/job-roles", "{\"title\":\"Clerk\",\"departmentId\":\"" + sales + "\"}")
                .andExpect(status().isConflict());
    }
}
