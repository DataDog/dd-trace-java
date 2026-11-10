package dd.trace.instrumentation.springwebflux.server;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

// Kept apart from TestController so its exception handler only sees these endpoints.
@RestController
public class ErrorPathController {
  @GetMapping("/handled-error/{id}")
  public Mono<String> handledError(@PathVariable("id") long id) {
    return Mono.error(new IllegalArgumentException("handled " + id));
  }

  @GetMapping("/status-error/{id}")
  public Mono<String> statusError(@PathVariable("id") long id) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, "conflict " + id);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(e.getMessage());
  }
}
