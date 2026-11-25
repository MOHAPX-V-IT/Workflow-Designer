package app.process;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
class Accounts implements UserDetailsService {
    public enum Role {
        ADMIN("Администратор"), EMPLOYEE("Сотрудник"), APPROVER("Согласующий"), EXECUTOR("Исполнитель");
        private final String label;
        Role(String label) { this.label = label; }
        public String label() { return label; }
    }

    public record Person(long id, String name, String email, Role role) {
        public boolean administrator() { return role == Role.ADMIN; }
    }

    private static final RowMapper<Person> PERSON = (row, index) -> new Person(row.getLong("id"),
        row.getString("name"), row.getString("email"), Role.valueOf(row.getString("role")));
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;

    Accounts(JdbcTemplate db, PasswordEncoder passwords) {
        this.db = db;
        this.passwords = passwords;
    }

    @Override
    public UserDetails loadUserByUsername(String input) {
        String email = input.strip().toLowerCase(Locale.ROOT);
        return db.query("SELECT email,password_hash,role FROM work_account WHERE email=?", (row, index) ->
            User.withUsername(row.getString("email")).password(row.getString("password_hash"))
                .roles(row.getString("role")).build(), email).stream().findFirst()
            .orElseThrow(() -> new UsernameNotFoundException("Unknown account"));
    }

    Person byEmail(String email) {
        return db.query("SELECT id,name,email,role FROM work_account WHERE email=?", PERSON, email)
            .stream().findFirst().orElseThrow(Problem::forbidden);
    }

    Person byId(long id) {
        return db.query("SELECT id,name,email,role FROM work_account WHERE id=?", PERSON, id)
            .stream().findFirst().orElseThrow(() -> Problem.invalid("Выбранный сотрудник недоступен."));
    }

    List<Person> all() {
        return db.query("SELECT id,name,email,role FROM work_account ORDER BY name,id", PERSON);
    }

    boolean initialized() {
        return db.queryForObject("SELECT COUNT(*) FROM work_account", Long.class) > 0;
    }

    Person create(String name, String email, String password, Role role) {
        String display = Blueprint.required(name, 100, "Имя");
        String normalized = Blueprint.required(email, 150, "Рабочая почта").toLowerCase(Locale.ROOT);
        if (!normalized.matches("[^\\s@]+@[^\\s@.]+(\\.[^\\s@.]+)+")) throw Problem.invalid("Введите корректный адрес почты.");
        if (password == null || password.length() < 14 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw Problem.invalid("Используйте пароль от 14 символов и не более 72 байт.");
        }
        try {
            db.update("INSERT INTO work_account(name,email,password_hash,role) VALUES (?,?,?,?)",
                display, normalized, passwords.encode(password), role.name());
        } catch (DuplicateKeyException duplicate) {
            throw Problem.conflict("Учётная запись с такой почтой уже существует.");
        }
        return byEmail(normalized);
    }
}
