package datadog.trace.instrumentation.pekkohttp;

import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;

import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.apache.pekko.http.scaladsl.model.HttpRequest;
import org.apache.pekko.http.scaladsl.model.HttpResponse;
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
    final ContextScope scope = DatadogWrapperHelper.createSpan(request);
    AgentSpan span = fromContext(scope.context());
    Future<HttpResponse> futureResponse;
    try {
      futureResponse = userHandler.apply(request);
    } catch (final Throwable t) {
      scope.close();
      DatadogWrapperHelper.finishSpan(span, t);
      throw t;
    }
    final Future<HttpResponse> wrapped =
        futureResponse.transform(
            new AbstractFunction1<HttpResponse, HttpResponse>() {
              @Override
              public HttpResponse apply(final HttpResponse response) {
                DatadogWrapperHelper.finishSpan(span, response);
                return response;
              }
            },
            new AbstractFunction1<Throwable, Throwable>() {
              @Override
              public Throwable apply(final Throwable t) {
                DatadogWrapperHelper.finishSpan(span, t);
                return t;
              }
            },
            executionContext);
    scope.close();
    return wrapped;
  }
}
