package datadog.trace.instrumentation.akkahttp;

import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import akka.http.scaladsl.util.FastFuture$;
import akka.stream.Materializer;
import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.akkahttp.appsec.BlockingResponseHelper;
import scala.Function1;
import scala.concurrent.Future;
import scala.runtime.AbstractFunction1;

public class DatadogAsyncHandlerWrapper
    extends AbstractFunction1<HttpRequest, Future<HttpResponse>> {
  private final Function1<HttpRequest, Future<HttpResponse>> userHandler;
  private final Materializer materializer;

  public DatadogAsyncHandlerWrapper(
      final Function1<HttpRequest, Future<HttpResponse>> userHandler,
      final Materializer materializer) {
    this.userHandler = userHandler;
    this.materializer = materializer;
  }

  @Override
  public Future<HttpResponse> apply(final HttpRequest request) {
    final AgentScope scope = DatadogWrapperHelper.createSpan(request);
    AgentSpan span = scope.span();
    Future<HttpResponse> futureResponse;

    // handle blocking in the beginning of the request
    Flow.Action.RequestBlockingAction rba;
    if ((rba = span.getRequestBlockingAction()) != null) {
      request.discardEntityBytes(materializer);
      HttpResponse response = BlockingResponseHelper.maybeCreateBlockingResponse(rba, request);
      span.getRequestContext().getTraceSegment().effectivelyBlocked();
      DatadogWrapperHelper.finishSpan(span, response);
      return FastFuture$.MODULE$.<HttpResponse>successful().apply(response);
    }

    try {
      futureResponse = userHandler.apply(request);
    } catch (final Throwable t) {
      scope.close();
      DatadogWrapperHelper.finishSpan(span, t);
      throw t;
    }

    final Future<HttpResponse> wrapped =
        futureResponse
            .recoverWith(
                RecoverFromBlockedExceptionPF.INSTANCE_FUTURE, materializer.executionContext())
            .transform(
                new AbstractFunction1<HttpResponse, HttpResponse>() {
                  @Override
                  public HttpResponse apply(HttpResponse response) {
                    // handle blocking at the middle/end of the request
                    HttpResponse newResponse =
                        BlockingResponseHelper.handleFinishForWaf(span, response);
                    if (newResponse != response) {
                      span.getRequestContext().getTraceSegment().effectivelyBlocked();
                      response.entity().discardBytes(materializer);
                      response = newResponse;
                    }

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
                materializer.executionContext());
    scope.close();
    return wrapped;
  }
}
