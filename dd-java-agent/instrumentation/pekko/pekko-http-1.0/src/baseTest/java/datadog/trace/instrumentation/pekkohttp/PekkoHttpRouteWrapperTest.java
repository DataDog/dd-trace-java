package datadog.trace.instrumentation.pekkohttp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.ContextScope;
import org.apache.pekko.http.scaladsl.model.Uri;
import org.apache.pekko.http.scaladsl.server.RequestContext;
import org.apache.pekko.http.scaladsl.server.RequestContextImpl;
import org.apache.pekko.http.scaladsl.server.RouteResult;
import org.junit.jupiter.api.Test;
import scala.concurrent.ExecutionContextExecutor;
import scala.concurrent.Future;
import scala.concurrent.Future$;
import scala.concurrent.Promise;
import scala.concurrent.Promise$;
import scala.runtime.AbstractFunction1;

class PekkoHttpRouteWrapperTest {
  private static final ExecutionContextExecutor DIRECT =
      new ExecutionContextExecutor() {
        @Override
        public ExecutionContextExecutor prepare() {
          return this;
        }

        @Override
        public void execute(Runnable task) {
          task.run();
        }

        @Override
        public void reportFailure(Throwable failure) {
          throw new AssertionError(failure);
        }
      };

  @Test
  void delayedRejectedAlternativeCannotEraseACompletedSibling() {
    Uri.Path path = Uri.apply("/items/value").path();
    RequestContext request = new RequestContextImpl(null, path, DIRECT, null, null, null, null);
    PekkoHttpRouteState parent = new PekkoHttpRouteState(path);
    Promise<RouteResult> rejected = Promise$.MODULE$.apply();
    try (ContextScope ignored = parent.storeIn(Context.root()).attach()) {
      Future<RouteResult> losing =
          PekkoHttpRouteWrapper.wrap(
                  new AbstractFunction1<RequestContext, Future<RouteResult>>() {
                    @Override
                    public Future<RouteResult> apply(RequestContext ctx) {
                      PekkoHttpRouteState.current()
                          .matched(path, Uri.apply("").path(), "/loser/{param}");
                      return rejected.future();
                    }
                  })
              .apply(request);
      PekkoHttpRouteWrapper.wrap(
              new AbstractFunction1<RequestContext, Future<RouteResult>>() {
                @Override
                public Future<RouteResult> apply(RequestContext ctx) {
                  PekkoHttpRouteState.current()
                      .matched(path, Uri.apply("").path(), "/items/{param}");
                  return Future$.MODULE$.successful(
                      new RouteResult.Complete(
                          (org.apache.pekko.http.scaladsl.model.HttpResponse)
                              org.apache.pekko.http.javadsl.model.HttpResponse.create()));
                }
              })
          .apply(request);
      assertEquals("/items/{param}", parent.template());
      rejected.success(new RouteResult.Rejected(scala.collection.immutable.List$.MODULE$.empty()));
      assertTrue(losing.value().isDefined());
      assertEquals("/items/{param}", parent.template());
      assertEquals(parent, PekkoHttpRouteState.current());
    }
  }
}
