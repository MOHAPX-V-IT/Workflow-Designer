package app.process;

import static app.process.Blueprint.*;

import app.process.Accounts.Person;
import app.process.Accounts.Role;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class DemoScenarios {
    static final String PURCHASING = "office-purchasing-v1";
    private final Accounts accounts;
    private final WorkflowService workflow;
    private final JdbcTemplate db;

    DemoScenarios(Accounts accounts, WorkflowService workflow, JdbcTemplate db) {
        this.accounts = accounts;
        this.workflow = workflow;
        this.db = db;
    }

    void addOfficePurchasing() {
        var people = accounts.all().stream().collect(Collectors.toMap(Person::email, Function.identity()));
        Person admin = account(people, "admin", Role.ADMIN);
        Person employee = account(people, "employee", Role.EMPLOYEE);
        Person approver = account(people, "approver", Role.APPROVER);
        Person executor = account(people, "executor", Role.EXECUTOR);
        if (admin == null || employee == null || approver == null || executor == null) return;

        try {
            db.update("INSERT INTO flow_demo_scenario (scenario_key) VALUES (?)", PURCHASING);
        } catch (DuplicateKeyException alreadyInstalled) {
            return;
        }

        var definition = new Definition("Закупка для офиса · демо",
            "Проверьте заявку, согласуйте бюджет от 15 000 ₽, оформите заказ и подтвердите поставку.",
            List.of(new Field("item", "Что закупаем", FieldType.TEXT, true, List.of()),
                new Field("amount", "Сумма, ₽", FieldType.NUMBER, true, List.of()),
                new Field("department", "Категория расходов", FieldType.SELECT, true,
                    List.of("Оборудование", "Материалы", "Услуги")),
                new Field("purpose", "Зачем нужна закупка", FieldType.TEXT, true, List.of())),
            List.of(new Step("Проверить заявку", StepKind.APPROVAL, "role:APPROVER", 8, null),
                new Step("Согласовать бюджет", StepKind.APPROVAL, "role:APPROVER", 24,
                    new Rule("amount", Operator.GTE, BigDecimal.valueOf(15000))),
                new Step("Оформить заказ", StepKind.WORK, "role:EXECUTOR", 32, null),
                new Step("Подтвердить поставку", StepKind.WORK, "person:" + employee.id(), 72, null)));
        var template = workflow.save(admin, null, 0, definition, true);

        var chairs = launch(employee, template, "Два кресла для переговорной", "42800", "Оборудование",
            "Меняем кресла с повреждёнными механизмами. Аналоги есть у действующего поставщика.");
        workflow.advance(approver, chairs.id(), chairs.version(), Action.APPROVE, "Проверил заявку и предложение поставщика.");

        var cables = launch(employee, template, "Кабель и крепёж для рабочих мест", "6400", "Материалы",
            "Готовим четыре рабочих места в учебной зоне.");
        workflow.advance(approver, cables.id(), cables.version(), Action.APPROVE, "Комплект расходников указан полностью.");

        var printer = launch(employee, template, "Расходники для принтера", "7800", "Материалы",
            "Пополняем запас бумаги и картриджей для отдела продаж.");
        printer = workflow.advance(approver, printer.id(), printer.version(), Action.APPROVE, "Подтвердил остатки на складе.");
        printer = workflow.advance(executor, printer.id(), printer.version(), Action.COMPLETE, "Получили счёт, поставщик принял заказ.");
        workflow.advance(employee, printer.id(), printer.version(), Action.COMPLETE, "Два картриджа и пять пачек бумаги получены.");

        var screen = launch(employee, template, "Экран для учебной комнаты", "65000", "Оборудование",
            "Показываем презентации и записи обучения на встречах команды.");
        screen = workflow.advance(approver, screen.id(), screen.version(), Action.APPROVE, "Размер экрана подходит для комнаты.");
        workflow.advance(approver, screen.id(), screen.version(), Action.REJECT,
            "Текущий лимит 50 000 ₽. Запросите у поставщика вариант в пределах бюджета.");
    }

    private WorkflowService.Run launch(Person employee, WorkflowService.Template template,
                                       String item, String amount, String department, String purpose) {
        return workflow.launch(employee, template.id(), template.published(),
            Map.of("item", item, "amount", amount, "department", department, "purpose", purpose));
    }

    private static Person account(Map<String, Person> people, String handle, Role role) {
        Person person = people.get(handle + "@process.test");
        return person != null && person.role() == role ? person : null;
    }
}

