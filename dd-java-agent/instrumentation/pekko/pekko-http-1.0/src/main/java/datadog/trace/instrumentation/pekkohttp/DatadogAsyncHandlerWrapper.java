package datadog.trace.instrumentation.pekkohttp;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.api.InstrumenterConfig;
import org.apache.pekko.http.scaladsl.model.HttpRequest;
import org.apache.pekko.http.scaladsl.model.HttpResponse;
import scala.Function1;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;
import scala.concurrent.Promise;
import scala.concurrent.Promise$;
import scala.runtime.AbstractFunction1;
import scala.util.Failure;
import scala.util.Success;
import scala.util.Try;

public class DatadogAsyncHandlerWrapper
    extends AbstractFunction1<HttpRequest, Future<HttpResponse>> {
  private static final boolean COMPLETION_PRIORITY =
      InstrumenterConfig.get().isScalaPromiseCompletionPriorityEnabled();

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
    final Context context = scope.context();
    Future<HttpResponse> futureResponse;
    try {
      futureResponse = userHandler.apply(request);
    } catch (final Throwable t) {
      scope.close();
      DatadogWrapperHelper.finishSpan(context, t);
      throw t;
    }
    scope.close();
    final Promise<HttpResponse> wrapped = Promise$.MODULE$.apply();
    futureResponse.onComplete(
        new AbstractFunction1<Try<HttpResponse>, Void>() {
          @Override
          public Void apply(final Try<HttpResponse> result) {
            Try<HttpResponse> wrappedResult = result;
            try {
              if (result.isSuccess()) {
                DatadogWrapperHelper.finishSpan(context, result.get());
              } else {
                DatadogWrapperHelper.finishSpan(
                    context, ((Failure<HttpResponse>) result).exception());
              }
            } catch (final Throwable t) {
              // Preserve transform's behavior when span decoration fails. Pekko does not support
              // response blocking, and this wrapper has no Materializer for discarding the
              // successful response entity that is replaced by this failure.
              wrappedResult = new Failure<>(t);
            }
            final Try<HttpResponse> resultForPekko;
            if (COMPLETION_PRIORITY) {
              // Completion-priority mode can associate context directly with a Try. Copy the
              // result so neither that association nor the active thread context reaches Pekko.
              resultForPekko =
                  wrappedResult.isSuccess()
                      ? new Success<>(wrappedResult.get())
                      : new Failure<>(((Failure<HttpResponse>) wrappedResult).exception());
            } else {
              resultForPekko = wrappedResult;
            }
            // The application Future can complete while the request context is active. Complete
            // the Future exposed to Pekko under the root context so its framework callbacks do not
            // capture and retain the request trace.
            try (ContextScope ignored = Context.root().attach()) {
              wrapped.complete(resultForPekko);
            }
            return null;
          }
        },
        executionContext);
    return wrapped.future();
  }
}
