package datadog.trace.instrumentation.springweb6.boot;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Throws exceptions that are resolved to client errors outside this controller: by {@code
 * ResponseStatusExceptionResolver}, and by {@link ErrorPathControllerAdvice}. Unlike {@code
 * TestController}, it has no local {@code @ExceptionHandler}.
 */
@RestController
public class ErrorPathController {
  public static final String RESPONSE_STATUS_PATH = "/errors/response-status";
  public static final String ADVICE_PATH = "/errors/advice";

  @GetMapping(RESPONSE_STATUS_PATH)
  public String responseStatus() {
    throw new ResponseStatusException(HttpStatus.CONFLICT, "response status exception");
  }

  @GetMapping(ADVICE_PATH)
  public String advice() {
    throw new AdviceHandledException("handled by controller advice");
  }

  public static class AdviceHandledException extends RuntimeException {
    public AdviceHandledException(final String message) {
      super(message);
    }
  }
}
