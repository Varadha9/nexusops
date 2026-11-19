package com.nexusops.hrms;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.shared.TenantContext;
import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHr;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes for one employee that race each other are serialized on the per-employee locks (hr-leave-*,
 * hr-training-*). Each test runs one write in a transaction held open until the competing request is either
 * done or waiting on an advisory lock; it then commits, and the competitor must see the committed state.
 * I-1: a request re-reads the employment under its lock. M-4: editing skills waits for a completion.
 */
@AutoConfigureMockMvc
class PerEmployeeLocksIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired EmployeeService employees;
    @Autowired TrainingService training;
    @Autowired TransactionTemplate tx;

    Workspace ws;
    Api owner;
    UUID ownerUser;
    UUID meera;
    ExecutorService pool;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hrrace"));
        owner = Api.login(mvc, ws);
        TestHr.enable(owner);
        meera = TestHr.hire(owner, "Meera", "Iyer", "E-1");
        ownerUser = OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select id from users where email = ?",
                UUID.class, ws.email());
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    @Test
    void leaveRequestedWhileEmploymentIsEndingCantStartAfterTheLastDay() throws Exception {
        UUID annual = TestHr.leaveType(owner, "Annual leave");
        MockHttpServletResponse response = race(end(LocalDate.of(2027, 4, 30)), () -> owner.post(
                "/api/v1/hr/leave-requests", "{\"employeeId\":\"" + meera + "\",\"typeId\":\"" + annual
                        + "\",\"startDate\":\"2027-05-03\",\"endDate\":\"2027-05-03\"}").andReturn().getResponse());
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains(LeaveService.AFTER_EMPLOYMENT);
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select count(*) from leave_requests "
                + "where employee_id = ? and status in ('PENDING', 'APPROVED') and start_date > date '2027-04-30'",
                Long.class, meera)).isZero();
    }

    @Test
    void trainingAssignedWhileEmploymentIsEndingIsRefusedOnceTheyHaveLeft() throws Exception {
        UUID skill = TestHr.skill(owner, "Negotiation");
        UUID course = Api.id(owner.post("/api/v1/hr/courses", "{\"title\":\"Negotiation\",\"skills\":[{\"skillId\":\""
                + skill + "\",\"level\":3}]}"));
        MockHttpServletResponse response = race(end(LocalDate.now(ZoneOffset.UTC).minusDays(1)),
                () -> owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + course
                        + "\",\"employeeId\":\"" + meera + "\"}").andReturn().getResponse());
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString()).contains(TrainingService.LEFT);
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select count(*) from training_assignments "
                + "where employee_id = ? and status = 'ASSIGNED'", Long.class, meera)).isZero();
    }

    @Test
    void editingSkillsWhileATrainingIsCompletedWaitsInsteadOfFailing() throws Exception {
        UUID skill = TestHr.skill(owner, "Negotiation");
        UUID course = Api.id(owner.post("/api/v1/hr/courses", "{\"title\":\"Negotiation\",\"skills\":[{\"skillId\":\""
                + skill + "\",\"level\":3}]}"));
        UUID assignment = Api.id(owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + course
                + "\",\"employeeId\":\"" + meera + "\"}"));
        long version = ((Number) Api.read(owner.get("/api/v1/hr/employees/" + meera), "$.version")).longValue();
        MockHttpServletResponse response = race(() -> training.complete(assignment, null, 0L),
                () -> owner.put("/api/v1/hr/employees/" + meera + "/skills", "{\"skills\":[{\"skillId\":\"" + skill
                        + "\",\"level\":2}],\"version\":" + version + "}").andReturn().getResponse());
        // the completion raised the skill and bumped the employee, so the edit is now stale: a 409, never a 500
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select level from employee_skills "
                + "where employee_id = ? and skill_id = ?", Integer.class, meera, skill)).isEqualTo(3);
    }

    private Runnable end(LocalDate endDate) throws Exception {
        long version = ((Number) Api.read(owner.get("/api/v1/hr/employees/" + meera), "$.version")).longValue();
        return () -> employees.end(meera, new EndEmployment(endDate, "Moving abroad"), version);
    }

    /**
     * Runs {@code holder} in a transaction that stays open until {@code competitor} has finished or is blocked
     * on an advisory lock; then commits and returns the competitor's response.
     */
    private MockHttpServletResponse race(Runnable holder, Callable<MockHttpServletResponse> competitor)
            throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        Future<?> holding = pool.submit(() -> {
            try (TenantContext.Scope scope = TenantContext.open(ws.tenantId(), ownerUser)) {
                tx.executeWithoutResult(s -> {
                    holder.run();
                    held.countDown();
                    try {
                        commit.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        });
        assertThat(held.await(10, TimeUnit.SECONDS)).as("the first write ran").isTrue();
        Future<MockHttpServletResponse> request = pool.submit(competitor);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!request.isDone() && waitingOnAdvisoryLocks() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        commit.countDown();
        holding.get(10, TimeUnit.SECONDS);
        return request.get(10, TimeUnit.SECONDS);
    }

    private static long waitingOnAdvisoryLocks() {
        return OwnerJdbc.jdbc().queryForObject("select count(*) from pg_locks where locktype = 'advisory' and not granted",
                Long.class);
    }
}
