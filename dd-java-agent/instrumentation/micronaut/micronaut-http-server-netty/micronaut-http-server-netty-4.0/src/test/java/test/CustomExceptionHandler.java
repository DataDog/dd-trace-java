package test;

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import java.util.InputMismatchException;

@Singleton
class CustomExceptionHandler
    implements ExceptionHandler<InputMismatchException, HttpResponse<String>> {
  @Override
  public HttpResponse<String> handle(HttpRequest request, InputMismatchException exception) {
    return HttpResponse.<String>status(HttpStatus.valueOf(CUSTOM_EXCEPTION.getStatus()))
        .body(exception.getMessage());
  }
}
