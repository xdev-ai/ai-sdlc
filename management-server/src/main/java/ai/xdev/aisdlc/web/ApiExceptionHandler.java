package ai.xdev.aisdlc.web;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail invalid(IllegalArgumentException exception) { return problem(HttpStatus.BAD_REQUEST, "invalid-request", exception.getMessage()); }
  @ExceptionHandler(SecurityException.class)
  ProblemDetail forbidden(SecurityException exception) { return problem(HttpStatus.FORBIDDEN, "forbidden", exception.getMessage()); }
  @ExceptionHandler(IllegalStateException.class)
  ProblemDetail conflict(IllegalStateException exception) { return problem(HttpStatus.CONFLICT, "state-conflict", exception.getMessage()); }
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail validation(MethodArgumentNotValidException exception) {
    ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-failed", "Request validation failed");
    Map<String, String> errors = new LinkedHashMap<>();
    exception.getBindingResult().getFieldErrors().forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));
    problem.setProperty("errors", errors);
    return problem;
  }

  private ProblemDetail problem(HttpStatus status, String type, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail == null ? status.getReasonPhrase() : detail);
    problem.setType(URI.create("https://aisdlc.dev/problems/" + type));
    problem.setTitle(status.getReasonPhrase());
    return problem;
  }
}
