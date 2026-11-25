package app.process;

import static app.process.Blueprint.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class EditorForm {
    private EditorForm() {}

    static Definition parse(HttpServletRequest request) {
        var fields = new ArrayList<Field>();
        String[][] inputs = columns(request, "fieldKey", "fieldLabel", "fieldType", "fieldRequired", "fieldChoices");
        for (int index = 0; index < inputs[0].length; index++) {
            var options = inputs[4][index].lines().map(String::strip).filter(value -> !value.isBlank()).toList();
            fields.add(new Field(inputs[0][index].strip(), inputs[1][index], choice(FieldType.class, inputs[2][index]),
                "true".equals(inputs[3][index]), options));
        }
        var steps = new ArrayList<Step>();
        String[][] stages = columns(request, "stepName", "stepKind", "stepAssignee", "stepHours",
            "ruleField", "ruleOperator", "ruleThreshold");
        for (int index = 0; index < stages[0].length; index++) {
            String conditional = stages[4][index].strip();
            Rule rule = conditional.isEmpty() ? null : new Rule(conditional, choice(Operator.class, stages[5][index]),
                number(stages[6][index], "Порог условия"));
            int hours;
            try { hours = Integer.parseInt(stages[3][index]); }
            catch (NumberFormatException invalid) { throw Problem.invalid("Введите срок этапа в целых часах."); }
            steps.add(new Step(stages[0][index], choice(StepKind.class, stages[1][index]), stages[2][index], hours, rule));
        }
        return new Definition(request.getParameter("title"), request.getParameter("summary"), fields, steps);
    }

    private static String[][] columns(HttpServletRequest request, String... names) {
        String[][] columns = Arrays.stream(names).map(key -> {
            String[] values = request.getParameterValues(key);
            return values == null ? new String[0] : values;
        }).toArray(String[][]::new);
        int size = columns[0].length;
        if (size > 12 || Arrays.stream(columns).anyMatch(values -> values.length != size)) {
            throw Problem.invalid("Проверьте поля и этапы конструктора.");
        }
        return columns;
    }

    private static <T extends Enum<T>> T choice(Class<T> type, String value) {
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException invalid) { throw Problem.invalid("Выберите допустимое значение в форме."); }
    }

    static Definition starter() {
        return new Definition("", "",
            List.of(new Field("subject", "Тема заявки", FieldType.TEXT, true, List.of())),
            List.of(new Step("Согласовать заявку", StepKind.APPROVAL, "role:APPROVER", 24, null),
                new Step("Выполнить заявку", StepKind.WORK, "role:EXECUTOR", 48, null)));
    }
}
