package server;

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import play.http.HttpErrorHandler;
import play.mvc.Http.RequestHeader;
import play.mvc.Result;
import play.mvc.Results;

public class TestHttpErrorHandler implements HttpErrorHandler {
  static class CustomRuntimeException extends RuntimeException {
    public CustomRuntimeException(String message) {
      super(message);
    }
  }

  public CompletionStage<Result> onClientError(
      RequestHeader request, int statusCode, String message) {
    return CompletableFuture.completedFuture(Results.status(statusCode, message));
  }

  public CompletionStage<Result> onServerError(RequestHeader request, Throwable exception) {
    Throwable cause = exception.getCause();
    if (cause instanceof CustomRuntimeException) {
      return CompletableFuture.completedFuture(
          Results.status(CUSTOM_EXCEPTION.getStatus(), cause.getMessage()));
    }
    return CompletableFuture.completedFuture(
        Results.internalServerError(exception.getCause().getMessage()));
  }
}
