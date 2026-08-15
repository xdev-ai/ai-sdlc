package ai.xdev.aisdlc.web;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> invalid(IllegalArgumentException exception) { return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage())); }
  @ExceptionHandler(SecurityException.class)
  ResponseEntity<Map<String, String>> forbidden(SecurityException exception) { return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", exception.getMessage())); }
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException exception) { return ResponseEntity.badRequest().body(Map.of("error", "Request validation failed")); }
}

