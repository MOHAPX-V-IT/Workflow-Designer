package app.process;

import org.springframework.http.HttpStatus;

final class Problem extends RuntimeException {
    final HttpStatus status;

    Problem(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    static Problem invalid(String message) { return new Problem(HttpStatus.BAD_REQUEST, message); }
    static Problem missing(String message) { return new Problem(HttpStatus.NOT_FOUND, message); }
    static Problem conflict(String message) { return new Problem(HttpStatus.CONFLICT, message); }
    static Problem forbidden() { return new Problem(HttpStatus.FORBIDDEN, "У вас нет доступа к этому действию."); }
}
