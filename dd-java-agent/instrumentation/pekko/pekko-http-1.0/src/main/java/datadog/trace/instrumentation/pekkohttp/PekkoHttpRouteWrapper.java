package datadog.trace.instrumentation.pekkohttp;

import datadog.context.Context;
import datadog.context.ContextScope;
import org.apache.pekko.http.scaladsl.server.RequestContext;
import org.apache.pekko.http.scaladsl.server.RouteResult;
import scala.Function1;
import scala.Option;
import scala.concurrent.Future;
import scala.runtime.AbstractFunction1;
import scala.runtime.AbstractPartialFunction;
import scala.runtime.BoxedUnit;
import scala.util.Try;

/** Isolates a directive invocation until its route result is known. */
public final class PekkoHttpRouteWrapper
    extends AbstractFunction1<RequestContext, Future<RouteResult>> {
  private final Function1<RequestContext, Future<RouteResult>> delegate;

  private PekkoHttpRouteWrapper(Function1<RequestContext, Future<RouteResult>> delegate) {
    this.delegate = delegate;
  }

  public static Function1<RequestContext, Future<RouteResult>> wrap(
      Function1<RequestContext, Future<RouteResult>> route) {
    return route instanceof PekkoHttpRouteWrapper ? route : new PekkoHttpRouteWrapper(route);
  }

  @Override
  public Future<RouteResult> apply(RequestContext request) {
    PekkoHttpRouteState parent = PekkoHttpRouteState.current();
    if (parent == null) {
      return delegate.apply(request);
    }
    PekkoHttpRouteState child = parent.fork();
    try (ContextScope ignored = child.storeIn(Context.current()).attach()) {
      Future<RouteResult> future;
      try {
        future = delegate.apply(request);
      } catch (Throwable failure) {
        parent.select(child);
        throw failure;
      }
      Option<Try<RouteResult>> value = future.value();
      if (value.isDefined()) {
        select(parent, child, value.get());
        return future;
      }
      return future.andThen(
          new AbstractPartialFunction<Try<RouteResult>, BoxedUnit>() {
            @Override
            public boolean isDefinedAt(Try<RouteResult> result) {
              return true;
            }

            @Override
            public BoxedUnit apply(Try<RouteResult> result) {
              select(parent, child, result);
              return BoxedUnit.UNIT;
            }
          },
          request.executionContext());
    }
  }

  private static void select(
      PekkoHttpRouteState parent, PekkoHttpRouteState child, Try<RouteResult> result) {
    // Exceptions may be converted to a response by an enclosing exception handler.
    if (result.isFailure() || result.get() instanceof RouteResult.Complete) {
      parent.select(child);
    }
  }
}
