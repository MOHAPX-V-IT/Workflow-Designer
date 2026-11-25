package app.process;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Blueprint {
    private Blueprint() {}

    public enum FieldType {
        TEXT("Текст"), NUMBER("Число"), SELECT("Выбор из списка");
        private final String label;
        FieldType(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum StepKind {
        APPROVAL("Согласование"), WORK("Выполнение");
        private final String label;
        StepKind(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum Operator {
        GTE("≥"), GT(">"), LTE("≤"), LT("<"), EQ("=");
        private final String label;
        Operator(String label) { this.label = label; }
        public String label() { return label; }
        boolean accepts(int comparison) {
            return switch (this) {
                case GTE -> comparison >= 0;
                case GT -> comparison > 0;
                case LTE -> comparison <= 0;
                case LT -> comparison < 0;
                case EQ -> comparison == 0;
            };
        }
    }

    public enum State {
        ACTIVE("В работе"), COMPLETED("Завершено"), REJECTED("Отклонено");
        private final String label;
        State(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum Action {
        START("Запуск"), APPROVE("Согласование"), REJECT("Отказ"), COMPLETE("Выполнение");
        private final String label;
        Action(String label) { this.label = label; }
        public String label() { return label; }
    }

    public record Field(String key, String label, FieldType type, boolean required, List<String> choices) {
        public String choicesText() { return String.join("\n", choices); }
    }
    public record Rule(String field, Operator operator, BigDecimal threshold) {}
    public record Step(String name, StepKind kind, String assignee, int hours, Rule rule) {}
    public record Definition(String title, String summary, List<Field> fields, List<Step> steps) {}

    static int nextStep(Definition definition, Map<String, String> values, int previous) {
        for (int index = previous + 1; index < definition.steps().size(); index++) {
            Rule rule = definition.steps().get(index).rule();
            if (rule == null || rule.operator().accepts(new BigDecimal(values.get(rule.field())).compareTo(rule.threshold()))) {
                return index;
            }
        }
        return -1;
    }

    static Map<String, String> validateValues(Definition definition, Map<String, String> submitted) {
        var values = new LinkedHashMap<String, String>();
        for (Field field : definition.fields()) {
            String value = clean(submitted.get(field.key()), 2000, field.label());
            if (value.isEmpty()) {
                if (field.required()) throw Problem.invalid("Заполните поле «" + field.label() + "».");
            } else if (field.type() == FieldType.NUMBER) {
                value = number(value, field.label()).toPlainString();
            } else if (field.type() == FieldType.SELECT && !field.choices().contains(value)) {
                throw Problem.invalid("Выберите допустимое значение поля «" + field.label() + "».");
            }
            values.put(field.key(), value);
        }
        return values;
    }

    static BigDecimal number(String value, String label) {
        if (value == null || !value.strip().matches("-?\\d{1,12}([.,]\\d{1,4})?")) {
            throw Problem.invalid("Введите число в поле «" + label + "». Допустимо до четырёх знаков после запятой.");
        }
        return new BigDecimal(value.strip().replace(',', '.'));
    }

    static String required(String value, int limit, String label) {
        String clean = clean(value, limit, label);
        if (clean.isEmpty()) throw Problem.invalid("Заполните «" + label + "».");
        return clean;
    }

    static String clean(String value, int limit, String label) {
        String result = value == null ? "" : value.strip();
        if (result.length() > limit || result.codePoints().anyMatch(ch -> Character.isISOControl(ch)
                && ch != '\r' && ch != '\n' && ch != '\t')) {
            throw Problem.invalid("Проверьте поле «" + label + "». Максимум " + limit + " символов.");
        }
        return result;
    }
}
