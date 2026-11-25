package app.process;

import static app.process.Blueprint.*;

import app.process.Accounts.Role;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class Bootstrap implements ApplicationRunner {
    static final String DEMO_PASSWORD = "WorkshopDemo!2026";
    private final Accounts accounts;
    private final WorkflowService workflow;
    private final DemoScenarios demoScenarios;
    private final boolean enabled;
    private final boolean demo;
    private final String name;
    private final String email;
    private final String password;

    Bootstrap(Accounts accounts, WorkflowService workflow, DemoScenarios demoScenarios, @Value("${process.bootstrap}") boolean enabled,
              @Value("${process.demo}") boolean demo, @Value("${PROCESS_ADMIN_NAME:Администратор}") String name,
              @Value("${PROCESS_ADMIN_EMAIL:}") String email, @Value("${PROCESS_ADMIN_PASSWORD:}") String password) {
        this.accounts = accounts;
        this.workflow = workflow;
        this.demoScenarios = demoScenarios;
        this.enabled = enabled;
        this.demo = demo;
        this.name = name;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        if (!enabled) return;
        if (demo) {
            if (!accounts.initialized()) createDemoAccounts();
            demoScenarios.addOfficePurchasing();
            return;
        }
        if (accounts.initialized()) return;
        if (email.isBlank() || password.isBlank()) {
            throw new IllegalStateException("Для первой настройки задайте PROCESS_ADMIN_EMAIL и PROCESS_ADMIN_PASSWORD.");
        }
        accounts.create(name, email, password, Role.ADMIN);
    }

    private void createDemoAccounts() {
        var admin = accounts.create("Мария Воронова", "admin@process.test", DEMO_PASSWORD, Role.ADMIN);
        var employee = accounts.create("Анна Соколова", "employee@process.test", DEMO_PASSWORD, Role.EMPLOYEE);
        accounts.create("Илья Лебедев", "approver@process.test", DEMO_PASSWORD, Role.APPROVER);
        accounts.create("Вера Смирнова", "executor@process.test", DEMO_PASSWORD, Role.EXECUTOR);
        var definition = new Definition("Заявка на закупку", "Проверка бюджета и оформление закупки ответственным сотрудником.",
            List.of(new Field("item", "Что закупаем", FieldType.TEXT, true, List.of()),
                new Field("amount", "Сумма, ₽", FieldType.NUMBER, true, List.of()),
                new Field("department", "Категория расходов", FieldType.SELECT, true, List.of("Оборудование", "Материалы", "Услуги"))),
            List.of(new Step("Согласовать бюджет", StepKind.APPROVAL, "role:APPROVER", 24,
                    new Rule("amount", Operator.GTE, new BigDecimal("15000"))),
                new Step("Оформить закупку", StepKind.WORK, "role:EXECUTOR", 72, null)));
        var saved = workflow.save(admin, null, 0, definition, true);
        workflow.launch(employee, saved.id(), saved.published(),
            Map.of("item", "Два монитора для отдела поддержки", "amount", "38000", "department", "Оборудование"));
        workflow.launch(employee, saved.id(), saved.published(),
            Map.of("item", "Материалы для переговорной", "amount", "4200", "department", "Материалы"));
    }
}

