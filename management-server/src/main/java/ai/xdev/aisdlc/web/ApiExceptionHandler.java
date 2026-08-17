package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.config.RequestCorrelationFilter;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

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

  /**
   * A body the server cannot parse is the caller's error, not the server's.
   *
   * <p>Without this the catch-all below reports an unknown JSON field as {@code 500}. A client sending
   * {@code costMinor} instead of {@code sourceCostMinor} would see a server fault and have no way to learn which
   * field was wrong.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  ProblemDetail unreadable(HttpMessageNotReadableException exception) {
    String detail = exception.getMostSpecificCause() instanceof UnrecognizedPropertyException unknown
        ? "Unrecognized field '" + unknown.getPropertyName() + "'"
        : "The request body could not be read as JSON";
    return problem(HttpStatus.BAD_REQUEST, "malformed-request", detail);
  }

  /**
   * Anything not handled above.
   *
   * <p>Two behaviours, in order.
   *
   * <p>An exception that already declares its own status keeps it. Spring models these as {@link ErrorResponse} —
   * {@code ResponseStatusException} among them — and SCIM throws exactly that to refuse a missing or wrong bearer
   * token. Reporting a deliberate fail-closed {@code 401} as a {@code 500} would read as a platform outage rather
   * than a refused credential.
   *
   * <p>Everything else becomes a {@code 500} carrying a correlation id and nothing else. Before this handler
   * existed, an unhandled exception escaped the dispatcher, Tomcat re-dispatched to {@code /error}, and the
   * resource-server filter chain evaluated that internal forward as an unauthenticated request — so a broken SQL
   * statement reached the client as {@code 403} with {@code WWW-Authenticate: Bearer error="insufficient_scope"}.
   * An operator reading that would spend the incident checking token scopes for a fault unrelated to authorization.
   * The response carries no exception type, message, or stack frame; the correlation id is the join to the log,
   * which is where the detail belongs.
   */
  @ExceptionHandler(Throwable.class)
  ResponseEntity<ProblemDetail> unexpected(Throwable error) {
    if (error instanceof ErrorResponse declared) {
      return ResponseEntity.status(declared.getStatusCode()).body(declared.getBody());
    }
    log.error("Unhandled exception serving a control-plane request", error);
    ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
        "The request could not be completed. Quote the correlation id when reporting this.");
    String correlationId = MDC.get(RequestCorrelationFilter.MDC_KEY);
    if (correlationId != null) problem.setProperty("correlationId", correlationId);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
  }

  private ProblemDetail problem(HttpStatus status, String type, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail == null ? status.getReasonPhrase() : detail);
    problem.setType(URI.create("https://aisdlc.dev/problems/" + type));
    problem.setTitle(status.getReasonPhrase());
    return problem;
  }
}
