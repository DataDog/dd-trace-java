package datadog.trace.instrumentation.springweb6.boot;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ErrorPathController.class)
public class ErrorPathControllerAdvice {
  @ExceptionHandler(ErrorPathController.AdviceHandledException.class)
  public ProblemDetail handle(final ErrorPathController.AdviceHandledException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
  }
}
