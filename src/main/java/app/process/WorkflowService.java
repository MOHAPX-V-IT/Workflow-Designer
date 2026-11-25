package app.process;

import static app.process.Blueprint.*;

import app.process.Accounts.Person;
import app.process.Accounts.Role;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class WorkflowService {
    public record Template(long id, Definition definition, int revision, int published, OffsetDateTime updatedAt) {}
    public record Published(long templateId, int number, Definition definition, OffsetDateTime publishedAt) {}
    public record Run(long id, long templateId, int templateVersion, long creatorId, String creatorName,
                      Definition definition, Map<String, String> values, State state, Integer stepIndex,
                      Long assignedPerson, Role assignedRole, String assignedName, OffsetDateTime dueAt,
                      long version, OffsetDateTime updatedAt) {
        public Step currentStep() { return stepIndex == null ? null : definition.steps().get(stepIndex); }
        public boolean overdue() { return dueAt != null && dueAt.isBefore(now()); }
        public String assigneeLabel() {
            return assignedName != null ? assignedName : assignedRole != null ? assignedRole.label() : "—";
        }
    }
    public record Event(Action action, String stepName, String actorName, String note, OffsetDateTime at) {}
    public record Detail(Run run, List<Event> events, boolean canAct) {}
    private record Assignment(Integer step, Long person, Role role, OffsetDateTime deadline) {}
    private record Launch(Long runId, String payloadHash) {}
    private static final Assignment UNASSIGNED = new Assignment(null, null, null, null);
    private static final TypeReference<Map<String, String>> VALUES = new TypeReference<>() {};
    private static final String RUN_QUERY = """
        SELECT r.*,v.definition_json,initiator.name creator_name,assigned.name assigned_name
        FROM flow_run r JOIN flow_version v ON v.template_id=r.template_id AND v.number=r.template_version
        JOIN work_account initiator ON initiator.id=r.creator_id
        LEFT JOIN work_account assigned ON assigned.id=r.assigned_person
        """;
    private static final String INBOX = "r.state='ACTIVE' AND (r.assigned_person=? OR r.assigned_role=?)";
    private final JdbcTemplate db;
    private final Accounts accounts;
    private final ObjectMapper json;

    WorkflowService(JdbcTemplate db, Accounts accounts, ObjectMapper json) {
        this.db = db;
        this.accounts = accounts;
        this.json = json;
    }

    List<Template> templates(Person actor) {
        admin(actor);
        return db.query("SELECT * FROM flow_template ORDER BY updated_at DESC,id DESC", this::templateRow);
    }

    Template template(Person actor, long id) {
        admin(actor);
        return db.query("SELECT * FROM flow_template WHERE id=?", this::templateRow, id)
            .stream().findFirst().orElseThrow(() -> Problem.missing("Шаблон не найден."));
    }

    List<Published> published() {
        return db.query("""
            SELECT v.* FROM flow_version v JOIN flow_template t ON t.id=v.template_id AND t.published_version=v.number
            ORDER BY v.published_at DESC,v.template_id DESC
            """, this::versionRow);
    }

    List<Published> versions(Person actor, long id) {
        admin(actor);
        return db.query("SELECT * FROM flow_version WHERE template_id=? ORDER BY number DESC", this::versionRow, id);
    }

    Published published(long id, int revision) {
        return db.query("SELECT * FROM flow_version WHERE template_id=? AND number=?", this::versionRow, id, revision)
            .stream().findFirst().orElseThrow(() -> Problem.missing("Опубликованная версия не найдена."));
    }

    @Transactional
    Template save(Person actor, Long id, int revision, Definition input, boolean publish) {
        admin(actor);
        var definition = validated(input, publish);
        String encoded = encode(definition);
        var at = now();
        int previous = 0;
        if (id == null) {
            id = insert("INSERT INTO flow_template(title,draft_json,updated_at) VALUES (?,?,?)", definition.title(), encoded, at);
        } else {
            var current = template(actor, id);
            previous = current.published();
            int saved = db.update("""
                UPDATE flow_template SET title=?,draft_json=?,draft_revision=draft_revision+1,updated_at=?
                WHERE id=? AND draft_revision=?
                """, definition.title(), encoded, at, id, revision);
            if (saved != 1) throw Problem.conflict("Черновик изменился. Откройте последнюю версию и повторите правку.");
        }
        if (publish) {
            int next = previous + 1;
            db.update("INSERT INTO flow_version(template_id,number,definition_json,published_at,published_by) VALUES (?,?,?,?,?)",
                id, next, encoded, at, actor.id());
            db.update("UPDATE flow_template SET published_version=? WHERE id=?", next, id);
        }
        return template(actor, id);
    }

    UUID issueLaunch(Person actor, Published snapshot) {
        UUID nonce = UUID.randomUUID();
        db.update("""
            INSERT INTO flow_launch(nonce,creator_id,template_id,template_version) VALUES (?,?,?,?)
            """, nonce, actor.id(), snapshot.templateId(), snapshot.number());
        return nonce;
    }

    @Transactional
    Run launch(Person actor, Published snapshot, UUID nonce, Map<String, String> submitted) {
        var launch = db.query("""
            SELECT run_id,payload_hash FROM flow_launch
            WHERE nonce=? AND creator_id=? AND template_id=? AND template_version=? FOR UPDATE
            """, (row,index) -> new Launch(row.getObject("run_id", Long.class), row.getString("payload_hash")),
            nonce, actor.id(), snapshot.templateId(), snapshot.number()).stream().findFirst()
            .orElseThrow(() -> Problem.invalid("Эта форма недействительна. Откройте её заново из списка шаблонов."));
        String payloadHash = payloadHash(submitted);
        if (launch.runId() != null) {
            if (payloadHash.equals(launch.payloadHash())) return run(launch.runId());
            throw Problem.conflict("Форма уже отправлена с другими данными. Для новой заявки откройте новую форму.");
        }

        Run run = createRun(actor, snapshot, submitted);
        db.update("UPDATE flow_launch SET run_id=?,payload_hash=? WHERE nonce=?", run.id(), payloadHash, nonce);
        return run;
    }

    @Transactional
    Run launch(Person actor, long templateId, int templateVersion, Map<String, String> submitted) {
        return createRun(actor, published(templateId, templateVersion), submitted);
    }

    private Run createRun(Person actor, Published snapshot, Map<String, String> submitted) {
        Definition definition = snapshot.definition();
        var values = validateValues(definition, submitted);
        var at = now();
        var assignment = assignment(definition, values, -1, at);
        State state = assignment.step() == null ? State.COMPLETED : State.ACTIVE;
        long id = insert("""
            INSERT INTO flow_run(template_id,template_version,creator_id,payload_json,state,current_step,
                assigned_person,assigned_role,due_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """, snapshot.templateId(), snapshot.number(), actor.id(), encode(values), state.name(), assignment.step(),
            assignment.person(), name(assignment.role()), assignment.deadline(), at, at);
        event(id, Action.START, null, actor, "", at);
        return run(id);
    }

    List<Run> inbox(Person actor) {
        return db.query(RUN_QUERY + " WHERE " + INBOX + " ORDER BY r.due_at,r.id", this::runRow, actor.id(), actor.role().name());
    }

    List<Run> mine(Person actor) {
        return db.query(RUN_QUERY + " WHERE r.creator_id=? ORDER BY r.updated_at DESC,r.id DESC", this::runRow, actor.id());
    }

    List<Run> journal(Person actor) {
        String order = " ORDER BY r.updated_at DESC,r.id DESC";
        return actor.administrator() ? db.query(RUN_QUERY + order, this::runRow) : db.query(RUN_QUERY + """
            WHERE r.creator_id=? OR EXISTS(SELECT 1 FROM flow_event e WHERE e.run_id=r.id AND e.actor_id=?)
            """, this::runRow, actor.id(), actor.id());
    }

    Detail detail(Person actor, long id) {
        Run run = run(id);
        boolean assigned = canAct(actor, run);
        if (!assigned && !actor.administrator() && actor.id() != run.creatorId()) {
            long participated = db.queryForObject("SELECT COUNT(*) FROM flow_event WHERE run_id=? AND actor_id=?",
                Long.class, id, actor.id());
            if (participated == 0) throw missingRun();
        }
        var events = db.query("""
            SELECT e.action,e.step_name,a.name,e.note,e.occurred_at
            FROM flow_event e JOIN work_account a ON a.id=e.actor_id WHERE e.run_id=? ORDER BY e.id
            """, (row, index) -> new Event(Action.valueOf(row.getString("action")), row.getString("step_name"),
                row.getString("name"), row.getString("note"), time(row, "occurred_at")), id);
        return new Detail(run, events, assigned);
    }

    @Transactional
    Run advance(Person actor, long id, long version, Action action, String note) {
        Run current = detail(actor, id).run();
        if (current.version() != version || current.state() != State.ACTIVE) throw changedRun();
        if (!canAct(actor, current)) throw Problem.forbidden();
        Step step = current.currentStep();
        boolean correct = step.kind() == StepKind.APPROVAL ? (action == Action.APPROVE || action == Action.REJECT)
            : action == Action.COMPLETE;
        if (!correct) throw Problem.invalid("Действие недоступно на текущем этапе.");
        String comment = action == Action.REJECT ? required(note, 1000, "Причина отказа") : clean(note, 1000, "Комментарий");
        OffsetDateTime at = now();
        var next = action == Action.REJECT ? UNASSIGNED : assignment(current.definition(), current.values(), current.stepIndex(), at);
        State state = action == Action.REJECT ? State.REJECTED : next.step() == null ? State.COMPLETED : State.ACTIVE;
        int changed = db.update("""
            UPDATE flow_run SET state=?,current_step=?,assigned_person=?,assigned_role=?,due_at=?,updated_at=?,version=version+1
            WHERE id=? AND version=? AND state='ACTIVE'
            """, state.name(), next.step(), next.person(), name(next.role()), next.deadline(), at, id, version);
        if (changed != 1) throw changedRun();
        event(id, action, step.name(), actor, comment, at);
        return run(id);
    }

    private Definition validated(Definition definition, boolean publishing) {
        String title = required(definition.title(), 140, "Название шаблона");
        String summary = clean(definition.summary(), 600, "Описание");
        if (definition.fields().isEmpty() || definition.fields().size() > 12) throw Problem.invalid("Добавьте от 1 до 12 полей.");
        if (definition.steps().isEmpty() || definition.steps().size() > 12) throw Problem.invalid("Добавьте от 1 до 12 этапов.");

        var fields = new ArrayList<Field>();
        var byKey = new HashMap<String, Field>();
        for (Field field : definition.fields()) {
            String label = required(field.label(), 100, "Название поля");
            String key = required(field.key(), 32, "Ключ поля");
            if (!key.matches("[a-z][a-z0-9_]{0,31}") || byKey.containsKey(key)) {
                throw Problem.invalid("Ключ «" + key + "» должен быть уникальным и содержать строчные латинские буквы, цифры или подчёркивания.");
            }
            List<String> choices = field.type() == FieldType.SELECT ? field.choices().stream()
                .map(choice -> clean(choice, 100, label)).filter(choice -> !choice.isEmpty()).distinct().toList() : List.of();
            if (field.type() == FieldType.SELECT && (choices.size() < 2 || choices.size() > 20)) {
                throw Problem.invalid("Поле «" + label + "» должно содержать от 2 до 20 уникальных вариантов.");
            }
            var normalized = new Field(key, label, field.type(), field.required(), choices);
            fields.add(normalized);
            byKey.put(key, normalized);
        }

        var steps = new ArrayList<Step>();
        var people = accounts.all();
        for (Step step : definition.steps()) {
            String label = required(step.name(), 140, "Название этапа");
            if (step.hours() < 1 || step.hours() > 720) throw Problem.invalid("Срок этапа «" + label + "» должен быть от 1 до 720 часов.");
            var match = validateAssignee(step.assignee());
            if (match.person() != null && people.stream().noneMatch(person -> person.id() == match.person())
                    || publishing && match.role() != null && people.stream().noneMatch(person -> person.role() == match.role())) {
                throw Problem.invalid("Для этапа «" + label + "» выберите сотрудника или роль с действующим участником.");
            }
            if (step.rule() != null) {
                Field field = byKey.get(step.rule().field());
                if (field == null || field.type() != FieldType.NUMBER || !field.required()) {
                    throw Problem.invalid("Условие этапа «" + label + "» должно ссылаться на обязательное числовое поле.");
                }
            }
            steps.add(new Step(label, step.kind(), step.assignee(), step.hours(), step.rule()));
        }
        return new Definition(title, summary, List.copyOf(fields), List.copyOf(steps));
    }

    private record Assigned(Long person, Role role) {}

    private static Assigned validateAssignee(String source) {
        try {
            if (source != null && source.startsWith("person:")) {
                return new Assigned(Long.parseLong(source.substring(7)), null);
            }
            if (source != null && source.startsWith("role:")) {
                return new Assigned(null, Role.valueOf(source.substring(5)));
            }
        } catch (IllegalArgumentException ignored) {
            // The same validation error covers invalid forms and unavailable assignments.
        }
        throw Problem.invalid("Выберите ответственного на каждом этапе.");
    }

    private static Assignment assignment(Definition definition, Map<String, String> values, int after, OffsetDateTime at) {
        int index = nextStep(definition, values, after);
        if (index < 0) return UNASSIGNED;
        Step step = definition.steps().get(index);
        var assigned = validateAssignee(step.assignee());
        return new Assignment(index, assigned.person(), assigned.role(), at.plusHours(step.hours()));
    }

    private static boolean canAct(Person actor, Run run) {
        return run.state() == State.ACTIVE
            && (Objects.equals(run.assignedPerson(), actor.id()) || run.assignedRole() == actor.role());
    }

    private static String name(Role role) { return role == null ? null : role.name(); }

    private static void admin(Person actor) {
        if (!actor.administrator()) throw Problem.forbidden();
    }

    private void event(long runId, Action action, String stepName, Person actor, String note, OffsetDateTime time) {
        db.update("INSERT INTO flow_event(run_id,action,step_name,actor_id,note,occurred_at) VALUES (?,?,?,?,?,?)",
            runId, action.name(), stepName, actor.id(), note, time);
    }

    private long insert(String sql, Object... values) {
        var key = new GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    private Run run(long id) {
        return db.query(RUN_QUERY + " WHERE r.id=?", this::runRow, id)
            .stream().findFirst().orElseThrow(WorkflowService::missingRun);
    }

    private Run runRow(ResultSet row, int index) throws SQLException {
        String role = row.getString("assigned_role");
        return new Run(row.getLong("id"), row.getLong("template_id"), row.getInt("template_version"),
            row.getLong("creator_id"), row.getString("creator_name"), decode(row.getString("definition_json")),
            decodeValues(row.getString("payload_json")), State.valueOf(row.getString("state")),
            row.getObject("current_step", Integer.class), row.getObject("assigned_person", Long.class),
            role == null ? null : Role.valueOf(role), row.getString("assigned_name"),
            time(row, "due_at"), row.getLong("version"), time(row, "updated_at"));
    }

    private Template templateRow(ResultSet row, int index) throws SQLException {
        return new Template(row.getLong("id"), decode(row.getString("draft_json")), row.getInt("draft_revision"),
            row.getInt("published_version"), time(row, "updated_at"));
    }

    private Published versionRow(ResultSet row, int index) throws SQLException {
        return new Published(row.getLong("template_id"), row.getInt("number"),
            decode(row.getString("definition_json")), time(row, "published_at"));
    }

    private static OffsetDateTime time(ResultSet row, String name) throws SQLException {
        return row.getObject(name, OffsetDateTime.class);
    }

    private String encode(Object source) {
        try { return json.writeValueAsString(source); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Failed to store workflow data", error); }
    }

    private String payloadHash(Map<String, String> submitted) {
        byte[] stable = encode(new TreeMap<>(submitted)).getBytes(StandardCharsets.UTF_8);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stable)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 is unavailable", error); }
    }

    private Definition decode(String source) {
        try { return json.readValue(source, Definition.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Failed to read workflow definition", error); }
    }

    private Map<String, String> decodeValues(String source) {
        try { return json.readValue(source, VALUES); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Failed to read workflow request", error); }
    }

    private static Problem missingRun() { return Problem.missing("Заявка не найдена."); }
    private static Problem changedRun() { return Problem.conflict("Заявка уже изменилась. Проверьте текущий этап и повторите действие."); }
    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
}
