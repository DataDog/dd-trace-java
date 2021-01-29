package datadog.trace.instrumentation.akkahttp;

import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import scala.Function1;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;
import scala.runtime.AbstractFunction1;

public class DatadogAsyncHandlerWrapper
    extends AbstractFunction1<HttpRequest, Future<HttpResponse>> {
  private final Function1<HttpRequest, Future<HttpResponse>> userHandler;
  private final ExecutionContext executionContext;

  public DatadogAsyncHandlerWrapper(
      final Function1<HttpRequest, Future<HttpResponse>> userHandler,
      final ExecutionContext executionContext) {
    this.userHandler = userHandler;
    this.executionContext = executionContext;
  }

  @Override
  public Future<HttpResponse> apply(final HttpRequest request) {
    final AgentScope scope = DatadogWrapperHelper.createSpan(request);
    Future<HttpResponse> futureResponse = null;
    try {
      futureResponse = userHandler.apply(request);
    } catch (final Throwable t) {
      scope.close();
      DatadogWrapperHelper.finishSpan(scope.span(), t);
      throw t;
    }
    final Future<HttpResponse> wrapped =
        futureResponse.transform(
            new AbstractFunction1<HttpResponse, HttpResponse>() {
              @Override
              public HttpResponse apply(final HttpResponse response) {
                DatadogWrapperHelper.finishSpan(scope.span(), response);
                return response;
              }
            },
            new AbstractFunction1<Throwable, Throwable>() {
              @Override
              public Throwable apply(final Throwable t) {
                DatadogWrapperHelper.finishSpan(scope.span(), t);
                return t;
              }
            },
            executionContext);
    scope.close();
    return wrapped;
  }
}
