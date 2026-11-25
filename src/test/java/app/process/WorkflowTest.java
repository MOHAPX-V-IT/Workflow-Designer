package app.process;

import static app.process.Blueprint.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.process.Accounts.Person;
import app.process.Accounts.Role;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {
    "process.bootstrap=false",
    "spring.datasource.url=jdbc:h2:mem:constructor-workflow-test;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowTest {
    @Autowired Accounts accounts;
    @Autowired WorkflowService workflow;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;

    private Person admin;
    private Person employee;
    private Person approver;
    private Person executor;
    private Person colleague;

    @BeforeAll
    void setup() {
        String password = "Test-Unique-Secret-2026";
        admin = accounts.create("Администратор", "admin@test.example", password, Role.ADMIN);
        employee = accounts.create("Инициатор", "employee@test.example", password, Role.EMPLOYEE);
        approver = accounts.create("Согласующий", "approve@test.example", password, Role.APPROVER);
        executor = accounts.create("Исполнитель", "executor@test.example", password, Role.EXECUTOR);
        colleague = accounts.create("Другой исполнитель", "another@test.example", password, Role.EXECUTOR);
    }

    @Test
    void routesNumericThresholdToApprovalOrDirectlyToTheNamedExecutor() {
        var template = workflow.save(admin, null, 0, definition("Проверка бюджета", "Утвердить сумму"), true);
        var low = workflow.launch(employee, template.id(), 1, values("14999,99"));
        assertThat(low.currentStep().kind()).isEqualTo(StepKind.WORK);
        assertThat(low.assignedPerson()).isEqualTo(executor.id());
        assertThat(low.dueAt()).isAfter(OffsetDateTime.now().plusHours(23));
        assertThat(workflow.inbox(executor)).extracting(WorkflowService.Run::id).contains(low.id());
        assertThat(workflow.inbox(colleague)).extracting(WorkflowService.Run::id).doesNotContain(low.id());
        assertThatThrownBy(() -> workflow.detail(colleague, low.id()))
            .isInstanceOfSatisfying(Problem.class, error -> assertThat(error.status).isEqualTo(HttpStatus.NOT_FOUND));

        var high = workflow.launch(employee, template.id(), 1, values("15000"));
        assertThat(high.currentStep().kind()).isEqualTo(StepKind.APPROVAL);
        assertThat(high.assignedRole()).isEqualTo(Role.APPROVER);
        assertThatThrownBy(() -> workflow.advance(employee, high.id(), 0, Action.APPROVE, ""))
            .isInstanceOfSatisfying(Problem.class, error -> assertThat(error.status).isEqualTo(HttpStatus.FORBIDDEN));
        var accepted = workflow.advance(approver, high.id(), 0, Action.APPROVE, "Подтверждено");
        assertThat(accepted.assignedPerson()).isEqualTo(executor.id());
        var completed = workflow.advance(executor, high.id(), accepted.version(), Action.COMPLETE, "Готово");
        assertThat(completed.state()).isEqualTo(State.COMPLETED);
        assertThat(completed.dueAt()).isNull();
        assertThat(workflow.detail(employee, high.id()).events()).extracting(WorkflowService.Event::action)
            .containsExactly(Action.START, Action.APPROVE, Action.COMPLETE);
    }

    @Test
    void storesPublishedVersionsSeparatelyAndDoesNotPublishDrafts() {
        var first = workflow.save(admin, null, 0, definition("Первая версия", "Решение версии 1"), true);
        var pinned = workflow.launch(employee, first.id(), first.published(), values("25000"));
        var draft = workflow.save(admin, first.id(), first.revision(), definition("Новый черновик", "Не выпускалось"), false);
        assertThat(workflow.published(first.id(), 1).definition().title()).isEqualTo("Первая версия");
        assertThat(workflow.published()).filteredOn(item -> item.templateId() == first.id())
            .extracting(WorkflowService.Published::number).containsExactly(1);
        var second = workflow.save(admin, first.id(), draft.revision(), definition("Вторая версия", "Решение версии 2"), true);
        assertThat(second.published()).isEqualTo(2);
        assertThat(workflow.detail(employee, pinned.id()).run().currentStep().name()).isEqualTo("Решение версии 1");
        assertThat(workflow.launch(employee, first.id(), 2, values("25000")).currentStep().name()).isEqualTo("Решение версии 2");
        assertThatThrownBy(() -> workflow.save(admin, first.id(), draft.revision(), definition("Старая форма", "Запоздала"), true))
            .isInstanceOfSatisfying(Problem.class, error -> assertThat(error.status).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void recordsOneDecisionAcrossConcurrentSubmissionsAndTagsOverdueWork() throws Exception {
        var template = workflow.save(admin, null, 0, definition("Параллельные решения", "Подтверждение"), true);
        var run = workflow.launch(employee, template.id(), 1, values("15000"));
        db.update("UPDATE flow_run SET due_at=? WHERE id=?", OffsetDateTime.now().minusHours(1), run.id());
        assertThat(workflow.detail(approver, run.id()).run().overdue()).isTrue();
        var gate = new CountDownLatch(1);
        Callable<Boolean> decide = () -> {
            if (!gate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Workers did not start");
            try {
                workflow.advance(approver, run.id(), run.version(), Action.REJECT, "Превышен бюджет");
                return true;
            } catch (Problem problem) {
                assertThat(problem.status).isEqualTo(HttpStatus.CONFLICT);
                return false;
            }
        };
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(decide);
            var second = workers.submit(decide);
            gate.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
        }
        var finalRun = workflow.detail(employee, run.id());
        assertThat(finalRun.run().state()).isEqualTo(State.REJECTED);
        assertThat(finalRun.events()).extracting(WorkflowService.Event::action).containsExactly(Action.START, Action.REJECT);
    }

    @Test
    void protectsAdminPagesAndRendersTheEditorAndLaunchForm() throws Exception {
        var template = workflow.save(admin, null, 0, definition("Форма проверки", "Этап"), true);
        http.perform(get("/admin/templates")).andExpect(status().is3xxRedirection());
        http.perform(get("/admin/templates").with(user(employee.email()).roles("EMPLOYEE")))
            .andExpect(status().isForbidden());
        http.perform(post("/admin/templates/new").with(user(admin.email()).roles("ADMIN")))
            .andExpect(status().isForbidden());
        http.perform(get("/admin/templates/" + template.id()).with(user(admin.email()).roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Опубликовать версию")));
        UUID nonce = openLaunch(template.id(), 1);
        http.perform(sendLaunch(employee, template.id(), 1, nonce, "1200"))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void issuesScopedLaunchFormsAndDeduplicatesConcurrentSubmission() throws Exception {
        var template = workflow.save(admin, null, 0, definition("Повтор отправки", "Проверка"), true);
        workflow.save(admin, template.id(), template.revision(), definition("Повтор версии 2", "Проверка"), true);
        UUID first = openLaunch(template.id(),1);
        UUID second = openLaunch(template.id(),1);
        assertThat(first).isNotEqualTo(second);
        http.perform(sendLaunch(employee, template.id(), 2, first, "1200")).andExpect(status().isBadRequest());
        http.perform(sendLaunch(colleague, template.id(), 1, first, "1200")).andExpect(status().isBadRequest());
        http.perform(sendLaunch(employee, template.id(), 1, first, "abc")).andExpect(status().isBadRequest());

        var gate = new CountDownLatch(1);
        Callable<String> submit = () -> {
            if (!gate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Workers did not start");
            return http.perform(sendLaunch(employee, template.id(), 1, first, "1200"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        };
        String result;
        try (var workers = Executors.newFixedThreadPool(2)) {
            var left = workers.submit(submit);
            var right = workers.submit(submit);
            gate.countDown();
            result = left.get(10, TimeUnit.SECONDS);
            assertThat(right.get(10, TimeUnit.SECONDS)).isEqualTo(result);
        }

        long runId = Long.parseLong(result.substring("/requests/".length()));
        assertThat(workflow.mine(employee)).filteredOn(run -> run.templateId() == template.id())
            .extracting(WorkflowService.Run::id).containsExactly(runId);
        assertThat(workflow.detail(employee, runId).events()).extracting(WorkflowService.Event::action)
            .containsExactly(Action.START);
        http.perform(sendLaunch(employee, template.id(), 1, first, "1200,00")).andExpect(status().isConflict());
        var separate = http.perform(sendLaunch(employee, template.id(), 1, second, "1200"))
            .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        assertThat(separate).isNotEqualTo(result);
    }

    private UUID openLaunch(long template, int version) throws Exception {
        var response = http.perform(get("/start/" + template).param("version", String.valueOf(version))
                .with(user(employee.email()).roles("EMPLOYEE")))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"value_amount\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"nonce\"")))
            .andReturn();
        return (UUID) response.getModelAndView().getModel().get("nonce");
    }

    private MockHttpServletRequestBuilder sendLaunch(Person actor, long template, int version, UUID nonce, String amount) {
        return post("/start/" + template).with(user(actor.email()).roles(actor.role().name())).with(csrf())
            .param("version", String.valueOf(version)).param("nonce", nonce.toString())
            .param("value_amount", amount).param("value_category", "Оборудование");
    }

    @Test
    void publishesAnOrderedConditionalWorkflowThroughTheEditorForm() throws Exception {
        http.perform(post("/admin/templates/new").with(user(admin.email()).roles("ADMIN")).with(csrf())
                .param("revision", "0").param("save", "publish").param("title", "Форма публикации")
                .param("fieldKey", "amount", "comment").param("fieldLabel", "Сумма", "Комментарий")
                .param("fieldType", "NUMBER", "TEXT").param("fieldRequired", "true", "false").param("fieldChoices", "", "")
                .param("stepName", "Принять решение", "Исполнить").param("stepKind", "APPROVAL", "WORK")
                .param("stepAssignee", "role:APPROVER", "person:" + executor.id()).param("stepHours", "24", "48")
                .param("ruleField", "amount", "").param("ruleOperator", "GTE", "GTE").param("ruleThreshold", "5000", ""))
            .andExpect(status().is3xxRedirection());
        var template = workflow.published().stream().filter(item -> item.definition().title().equals("Форма публикации"))
            .findFirst().orElseThrow();
        assertThat(template.number()).isEqualTo(1);
        var above = workflow.launch(employee, template.templateId(), 1, Map.of("amount", "5000"));
        var below = workflow.launch(employee, template.templateId(), 1, Map.of("amount", "4999"));
        assertThat(above.currentStep().name()).isEqualTo("Принять решение");
        assertThat(below.assignedPerson()).isEqualTo(executor.id());
    }

    private Definition definition(String title, String approval) {
        return new Definition(title, "Входящий запрос",
            List.of(new Field("amount", "Сумма", FieldType.NUMBER, true, List.of()),
                new Field("category", "Категория", FieldType.SELECT, true, List.of("Оборудование", "Услуги"))),
            List.of(new Step(approval, StepKind.APPROVAL, "role:APPROVER", 24,
                    new Rule("amount", Operator.GTE, BigDecimal.valueOf(15000))),
                new Step("Исполнить", StepKind.WORK, "person:" + executor.id(), 24, null)));
    }

    private static Map<String, String> values(String amount) {
        return Map.of("amount", amount, "category", "Оборудование");
    }
}
