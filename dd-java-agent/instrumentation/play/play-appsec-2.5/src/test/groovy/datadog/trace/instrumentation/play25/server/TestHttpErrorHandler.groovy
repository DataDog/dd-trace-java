package datadog.trace.instrumentation.play25.server

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import play.http.HttpErrorHandler
import play.mvc.Http.RequestHeader
import play.mvc.Result
import play.mvc.Results

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION

enum TestHttpErrorHandler implements HttpErrorHandler {
  INSTANCE

  private static final Logger LOG = LoggerFactory.getLogger(TestHttpErrorHandler)

  static class CustomRuntimeException extends RuntimeException {
    CustomRuntimeException(String message) {
      super(message)
    }
  }

  CompletionStage<Result> onClientError(
    RequestHeader request, int statusCode, String message) {
    return CompletableFuture.completedFuture(Results.status(statusCode, message))
  }

  CompletionStage<Result> onServerError(RequestHeader request, Throwable exception) {
    LOG.warn('server error', exception)

    Throwable cause = exception.getCause()
    if (cause) {
      exception = cause
    }
    if (exception instanceof CustomRuntimeException) {
      return CompletableFuture.completedFuture(
        Results.status(CUSTOM_EXCEPTION.status, exception.message))
    }

    CompletableFuture.completedFuture(Results.internalServerError(exception.message))
  }
}
