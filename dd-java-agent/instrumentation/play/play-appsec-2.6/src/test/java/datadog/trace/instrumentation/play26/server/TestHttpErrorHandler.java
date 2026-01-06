package datadog.trace.instrumentation.play26.server;

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.http.HttpErrorHandler;
import play.mvc.Http.RequestHeader;
import play.mvc.Result;
import play.mvc.Results;

public class TestHttpErrorHandler implements HttpErrorHandler {
  private static final Logger log = LoggerFactory.getLogger(TestHttpErrorHandler.class);

  public static class CustomRuntimeException extends RuntimeException {
    public CustomRuntimeException(String message) {
      super(message);
    }
  }

  public CompletionStage<Result> onClientError(
      RequestHeader request, int statusCode, String message) {
    return CompletableFuture.completedFuture(Results.status(statusCode, message));
  }

  public CompletionStage<Result> onServerError(RequestHeader request, Throwable exception) {
    log.warn("server error", exception);

    Throwable cause = exception.getCause();
    if (cause != null) {
      exception = cause;
    }
    if (exception instanceof CustomRuntimeException) {
      return CompletableFuture.completedFuture(
          Results.status(CUSTOM_EXCEPTION.getStatus(), exception.getMessage()));
    }

    return CompletableFuture.completedFuture(Results.internalServerError(exception.getMessage()));
  }
}
