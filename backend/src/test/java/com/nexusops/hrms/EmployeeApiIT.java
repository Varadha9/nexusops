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
class EmployeeApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID sales;
    UUID negotiation;
    UUID excel;
    UUID executive;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hremp"));
        owner = Api.login(mvc, ws);
        TestHr.enable(owner);
        sales = TestHr.department(owner, "Sales");
        negotiation = TestHr.skill(owner, "Negotiation");
        excel = TestHr.skill(owner, "Excel");
        executive = TestHr.jobRole(owner, "Account executive", sales, Map.of(negotiation, 4, excel, 2));
    }

    private UUID ownerUserId() {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select id from users where email = ?", UUID.class,
                ws.email());
    }

    @Test
    void hiringAnExistingPersonMakesThemAnEmployeeInTheDirectory() throws Exception {
        UUID person = Api.id(owner.post("/api/v1/persons", "{\"firstName\":\"Meera\",\"lastName\":\"Iyer\","
                + "\"email\":\"meera@deccan.test\"}").andExpect(status().isCreated()));
        UUID id = Api.id(owner.post("/api/v1/hr/employees", "{\"personId\":\"" + person + "\",\"employeeNumber\":\"E-7\","
                        + "\"departmentId\":\"" + sales + "\",\"jobRoleId\":\"" + executive + "\",\"employmentType\":"
                        + "\"FULL_TIME\",\"startDate\":\"2026-01-05\",\"userId\":\"" + ownerUserId() + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.person.name").value("Meera Iyer"))
                .andExpect(jsonPath("$.person.email").value("meera@deccan.test"))
                .andExpect(jsonPath("$.department.name").value("Sales"))
                .andExpect(jsonPath("$.jobRole.title").value("Account executive"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.user.id").value(ownerUserId().toString())));
        owner.get("/api/v1/parties/" + person)
                .andExpect(jsonPath("$.roles[?(@.role == 'EMPLOYEE')].status").value(Matchers.contains("ACTIVE")))
                .andExpect(jsonPath("$.roles[?(@.role == 'EMPLOYEE')].employeeNumber").value(Matchers.contains("E-7")))
                .andExpect(jsonPath("$.roles[?(@.role == 'EMPLOYEE')].since").value(Matchers.contains("2026-01-05")));
        // the directory may no longer edit that role by hand
        owner.put("/api/v1/parties/" + person + "/roles/EMPLOYEE", "{\"status\":\"INACTIVE\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This person's employment is managed in HRMS."));
        owner.get("/api/v1/hr/employees/" + id).andExpect(jsonPath("$.employeeNumber").value("E-7"));
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = 'EmployeeHired'", Long.class)).isEqualTo(1);
    }

    @Test
    void hiringANewPersonCreatesThemInTheDirectory() throws Exception {
        UUID id = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Ravi\",\"lastName\":\"Kumar\","
                + "\"email\":\"ravi@deccan.test\",\"phone\":\"+91 98\"},\"employeeNumber\":\"E-8\","
                + "\"startDate\":\"2099-01-04\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UPCOMING"))
                .andExpect(jsonPath("$.employmentType").value("FULL_TIME")));
        String person = Api.read(owner.get("/api/v1/hr/employees/" + id), "$.person.id");
        owner.get("/api/v1/parties/" + person).andExpect(jsonPath("$.kind").value("PERSON"))
                .andExpect(jsonPath("$.phone").value("+91 98"));
    }

    @Test
    void invalidHiresAreFieldErrors() throws Exception {
        UUID org = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Deccan Retail\"}"));
        String[][] cases = {
                {"{\"employeeNumber\":\"E-1\",\"startDate\":\"2026-01-05\"}", "personId"},
                {"{\"personId\":\"" + org + "\",\"employeeNumber\":\"E-1\",\"startDate\":\"2026-01-05\"}", "personId"},
                {"{\"personId\":\"" + UUID.randomUUID() + "\",\"employeeNumber\":\"E-1\",\"startDate\":\"2026-01-05\"}",
                        "personId"},
                {"{\"person\":{\"firstName\":\"A\",\"lastName\":\"B\"},\"employeeNumber\":\"\",\"startDate\":\"2026-01-05\"}",
                        "employeeNumber"},
                {"{\"person\":{\"firstName\":\"A\",\"lastName\":\"B\"},\"employeeNumber\":\"E-1\"}", "startDate"},
                {"{\"person\":{\"firstName\":\"A\",\"lastName\":\"B\"},\"employeeNumber\":\"E-1\",\"startDate\":\"2026-01-05\","
                        + "\"departmentId\":\"" + UUID.randomUUID() + "\"}", "departmentId"},
                {"{\"person\":{\"firstName\":\"A\",\"lastName\":\"B\"},\"employeeNumber\":\"E-1\",\"startDate\":\"2026-01-05\","
                        + "\"jobRoleId\":\"" + UUID.randomUUID() + "\"}", "jobRoleId"},
                {"{\"person\":{\"firstName\":\"A\",\"lastName\":\"B\"},\"employeeNumber\":\"E-1\",\"startDate\":\"2026-01-05\","
                        + "\"managerId\":\"" + UUID.randomUUID() + "\"}", "managerId"},
                {"{\"person\":{\"firstName\":\"A\",\"lastName\":\"B\"},\"employeeNumber\":\"E-1\",\"startDate\":\"2026-01-05\","
                        + "\"userId\":\"" + UUID.randomUUID() + "\"}", "userId"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/hr/employees", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
    }

    @Test
    void personsNumbersAndAccountsBelongToOneEmployeeEach() throws Exception {
        UUID person = Api.id(owner.post("/api/v1/persons", "{\"firstName\":\"Asha\",\"lastName\":\"Rao\"}"));
        owner.post("/api/v1/hr/employees", "{\"personId\":\"" + person + "\",\"employeeNumber\":\"E-1\","
                + "\"startDate\":\"2026-01-05\",\"userId\":\"" + ownerUserId() + "\"}").andExpect(status().isCreated());
        owner.post("/api/v1/hr/employees", "{\"personId\":\"" + person + "\",\"employeeNumber\":\"E-2\","
                + "\"startDate\":\"2026-01-05\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].message").value("This person already has an employee record."));
        owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"B\",\"lastName\":\"C\"},\"employeeNumber\":\"e-1\","
                + "\"startDate\":\"2026-01-05\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("employeeNumber"));
        owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"B\",\"lastName\":\"C\"},\"employeeNumber\":\"E-3\","
                + "\"startDate\":\"2026-01-05\",\"userId\":\"" + ownerUserId() + "\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].message").value("This team member is already linked to an employee."));
    }

    @Test
    void managerLoopsAreRefused() throws Exception {
        UUID a = TestHr.hire(owner, "Ann", "A", "A-1");
        UUID b = TestHr.hire(owner, "Ben", "B", "B-1");
        UUID c = TestHr.hire(owner, "Cy", "C", "C-1");
        owner.put("/api/v1/hr/employees/" + a, update("A-1", b, 0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.manager.name").value("Ben B"));
        owner.put("/api/v1/hr/employees/" + b, update("B-1", c, 0)).andExpect(status().isOk());
        owner.put("/api/v1/hr/employees/" + c, update("C-1", a, 0)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("managerId"))
                .andExpect(jsonPath("$.errors[0].message").value("A manager can't report to their own report."));
        owner.put("/api/v1/hr/employees/" + c, update("C-1", c, 0)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("managerId"));
        owner.get("/api/v1/hr/employees?managerId=" + c).andExpect(jsonPath("$.items[*].name").value(
                Matchers.contains("Ben B")));
    }

    @Test
    void anUpdateWithoutAnEmploymentTypeKeepsTheCurrentOne() throws Exception {
        UUID id = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Ivy\",\"lastName\":\"Jones\"},"
                + "\"employeeNumber\":\"I-1\",\"employmentType\":\"INTERN\",\"startDate\":\"2026-01-05\"}")
                .andExpect(status().isCreated()));
        owner.put("/api/v1/hr/employees/" + id, "{\"employeeNumber\":\"I-1\",\"startDate\":\"2026-01-05\","
                + "\"version\":0}").andExpect(status().isOk()).andExpect(jsonPath("$.employmentType").value("INTERN"));
    }

    private static String update(String number, UUID managerId, long version) {
        return "{\"employeeNumber\":\"" + number + "\",\"startDate\":\"2026-01-05\",\"employmentType\":\"FULL_TIME\","
                + "\"managerId\":" + (managerId == null ? "null" : "\"" + managerId + "\"") + ",\"version\":" + version
                + "}";
    }

    @Test
    void endingEmploymentTurnsTheDirectoryRoleOffAndReinstatingTurnsItOn() throws Exception {
        UUID id = TestHr.hire(owner, "Dev", "Patel", "E-9");
        String person = Api.read(owner.get("/api/v1/hr/employees/" + id), "$.person.id");
        String yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
        owner.post("/api/v1/hr/employees/" + id + "/end", "{\"endDate\":\"2025-12-31\",\"version\":0}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("endDate"));
        owner.post("/api/v1/hr/employees/" + id + "/end", "{\"endDate\":\"" + yesterday + "\",\"reason\":\"Resigned\","
                + "\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LEFT"))
                .andExpect(jsonPath("$.endReason").value("Resigned"));
        owner.get("/api/v1/parties/" + person)
                .andExpect(jsonPath("$.roles[?(@.role == 'EMPLOYEE')].status").value(Matchers.contains("INACTIVE")));
        owner.post("/api/v1/hr/employees/" + id + "/end", "{\"endDate\":\"" + yesterday + "\",\"version\":1}")
                .andExpect(status().isConflict());
        owner.get("/api/v1/hr/employees").andExpect(jsonPath("$.total").value(0));
        owner.get("/api/v1/hr/employees?status=LEFT").andExpect(jsonPath("$.items[*].name").value(
                Matchers.contains("Dev Patel")));
        owner.get("/api/v1/hr/employees?personId=" + person).andExpect(jsonPath("$.items[*].id").value(
                Matchers.contains(id.toString())));
        owner.post("/api/v1/hr/employees/" + id + "/reinstate", "{\"version\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.endDate").doesNotExist());
        owner.get("/api/v1/parties/" + person)
                .andExpect(jsonPath("$.roles[?(@.role == 'EMPLOYEE')].status").value(Matchers.contains("ACTIVE")));
        owner.post("/api/v1/hr/employees/" + id + "/reinstate", "{\"version\":2}").andExpect(status().isConflict());
    }

    @Test
    void employmentDatesOutsideTheSupportedRangeAreFieldErrors() throws Exception {
        for (String date : new String[] {"1899-12-31", "3000-01-01", "+6000000-01-01"}) {
            owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Far\",\"lastName\":\"Away\"},"
                    + "\"employeeNumber\":\"F-1\",\"startDate\":\"" + date + "\"}").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("startDate"))
                    .andExpect(jsonPath("$.errors[0].message").value("Enter a date from 1900 to 2999."));
        }
        UUID id = TestHr.hire(owner, "Dev", "Patel", "E-9");
        owner.put("/api/v1/hr/employees/" + id, "{\"employeeNumber\":\"E-9\",\"startDate\":\"3000-01-01\","
                + "\"version\":0}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("startDate"));
        owner.post("/api/v1/hr/employees/" + id + "/end", "{\"endDate\":\"+6000000-01-01\",\"version\":0}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("endDate"))
                .andExpect(jsonPath("$.errors[0].message").value("Enter a date from 1900 to 2999."));
    }

    @Test
    void listsAndFilters() throws Exception {
        UUID meera = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Meera\",\"lastName\":\"Iyer\"},"
                + "\"employeeNumber\":\"S-1\",\"departmentId\":\"" + sales + "\",\"jobRoleId\":\"" + executive
                + "\",\"startDate\":\"2026-01-05\"}"));
        TestHr.hire(owner, "Arun", "Shah", "O-1");
        owner.get("/api/v1/hr/employees").andExpect(jsonPath("$.items[*].name").value(
                Matchers.contains("Arun Shah", "Meera Iyer")));
        owner.get("/api/v1/hr/employees?departmentId=" + sales).andExpect(jsonPath("$.items[*].id").value(
                Matchers.contains(meera.toString())));
        owner.get("/api/v1/hr/employees?jobRoleId=" + executive).andExpect(jsonPath("$.total").value(1));
        owner.get("/api/v1/hr/employees?q=s-1").andExpect(jsonPath("$.items[*].name").value(Matchers.contains("Meera Iyer")));
        owner.get("/api/v1/hr/employees?q=shah").andExpect(jsonPath("$.items[*].name").value(Matchers.contains("Arun Shah")));
        owner.get("/api/v1/hr/employees?status=UPCOMING").andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void skillsAreSetAsAWholeAndShowTheGapsOfTheJobRole() throws Exception {
        UUID id = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Meera\",\"lastName\":\"Iyer\"},"
                + "\"employeeNumber\":\"S-1\",\"jobRoleId\":\"" + executive + "\",\"startDate\":\"2026-01-05\"}"));
        owner.put("/api/v1/hr/employees/" + id + "/skills", "{\"skills\":[{\"skillId\":\"" + negotiation
                        + "\",\"level\":2,\"note\":\"Learning\"},{\"skillId\":\"" + excel + "\",\"level\":5}],\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills[*].name").value(Matchers.contains("Excel", "Negotiation")))
                .andExpect(jsonPath("$.skills[1].note").value("Learning"));
        owner.get("/api/v1/hr/employees/" + id + "/skill-gap")
                .andExpect(jsonPath("$.jobRole.title").value("Account executive"))
                .andExpect(jsonPath("$.gaps.length()").value(1))
                .andExpect(jsonPath("$.gaps[0].skill.name").value("Negotiation"))
                .andExpect(jsonPath("$.gaps[0].required").value(4))
                .andExpect(jsonPath("$.gaps[0].held").value(2))
                .andExpect(jsonPath("$.gaps[0].recommendedCourses").isEmpty());
        String[][] cases = {
                {"[{\"skillId\":\"" + UUID.randomUUID() + "\",\"level\":2}]", "skills[0].skillId"},
                {"[{\"skillId\":\"" + excel + "\",\"level\":0}]", "skills[0].level"},
                {"[{\"skillId\":\"" + excel + "\",\"level\":2},{\"skillId\":\"" + excel + "\",\"level\":3}]",
                        "skills[1].skillId"},
                {"[{\"skillId\":\"" + excel + "\",\"level\":2,\"note\":\"" + "x".repeat(201) + "\"}]", "skills[0].note"},
        };
        for (String[] c : cases) {
            owner.put("/api/v1/hr/employees/" + id + "/skills", "{\"skills\":" + c[0] + ",\"version\":1}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        owner.put("/api/v1/hr/employees/" + id + "/skills", "{\"skills\":[],\"version\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.skills").isEmpty());
        owner.get("/api/v1/hr/employees/" + id + "/skill-gap").andExpect(jsonPath("$.gaps.length()").value(2))
                .andExpect(jsonPath("$.gaps[1].held").doesNotExist());
    }

    @Test
    void anEmployeeWithoutAJobRoleHasNoGaps() throws Exception {
        UUID id = TestHr.hire(owner, "Arun", "Shah", "O-1");
        owner.get("/api/v1/hr/employees/" + id + "/skill-gap").andExpect(jsonPath("$.jobRole").doesNotExist())
                .andExpect(jsonPath("$.gaps").isEmpty());
    }

    @Test
    void aDepartmentWithPeopleInItCantBeArchived() throws Exception {
        UUID id = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"M\",\"lastName\":\"I\"},"
                + "\"employeeNumber\":\"S-1\",\"departmentId\":\"" + sales + "\",\"startDate\":\"2026-01-05\"}"));
        owner.post("/api/v1/hr/departments/" + sales + "/archive", "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Move this department's employees first."));
        owner.post("/api/v1/hr/employees/" + id + "/end", "{\"endDate\":\"" + LocalDate.now(ZoneOffset.UTC).minusDays(1)
                + "\",\"version\":0}").andExpect(status().isOk());
        owner.post("/api/v1/hr/departments/" + sales + "/archive", "").andExpect(status().isOk());
    }

    @Test
    void employeesAreRecordsWithActivityAndAppearOnTheirPersonsTimeline() throws Exception {
        UUID id = TestHr.hire(owner, "Dev", "Patel", "E-9");
        String person = Api.read(owner.get("/api/v1/hr/employees/" + id), "$.person.id");
        owner.post("/api/v1/activities", "{\"subjectType\":\"EMPLOYEE\",\"subjectId\":\"" + id
                + "\",\"type\":\"NOTE\",\"summary\":\"Signed the contract\"}").andExpect(status().isCreated());
        owner.get("/api/v1/activities?subjectType=PARTY&subjectId=" + person + "&includeRelated=true")
                .andExpect(jsonPath("$.items[*].summary").value(Matchers.hasItem("Signed the contract")));
        owner.get("/api/v1/activities?subjectType=EMPLOYEE&subjectId=" + id)
                .andExpect(jsonPath("$.items[0].subject.label").value("Dev Patel · E-9"));
    }

    @Test
    void readersSeeEmployeesButOnlyUpdatersChangeThem() throws Exception {
        UUID id = TestHr.hire(owner, "Dev", "Patel", "E-9");
        UUID role = TestRoles.create(mvc, owner.session(), "HR viewer", "hr.employee.read");
        Api viewer = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        viewer.get("/api/v1/hr/employees/" + id).andExpect(status().isOk());
        viewer.post("/api/v1/hr/employees", "{}").andExpect(status().isForbidden());
        viewer.put("/api/v1/hr/employees/" + id + "/skills", "{\"skills\":[],\"version\":0}")
                .andExpect(status().isForbidden());
        viewer.post("/api/v1/hr/employees/" + id + "/end", "{}").andExpect(status().isForbidden());
        viewer.get("/api/v1/hr/members").andExpect(status().isForbidden());
        owner.get("/api/v1/hr/members?q=" + ws.email().substring(0, 5)).andExpect(jsonPath("$[0].email").value(ws.email()));
    }
}
