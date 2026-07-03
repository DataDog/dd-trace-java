import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the active span at the point of {@code subscribe()} is restored inside the
 * subscriber's callback, so any spans started during {@code onSuccess} are correctly parented.
 */
class SubscriptionTest extends AbstractInstrumentationTest {

  @Test
  void subscriberCallbackInheritsParentSpanFromSubscriptionSite() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);

    AgentSpan parent = startSpan("test", "parent");
    AgentScope scope = activateSpan(parent);
    try {
      Maybe<Connection> connection = Maybe.create(emitter -> emitter.onSuccess(new Connection()));
      connection.subscribe(
          c -> {
            c.query();
            latch.countDown();
          });
    } finally {
      scope.close();
      parent.finish();
    }

    assertTrue(latch.await(10, TimeUnit.SECONDS), "subscriber callback did not run in time");

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent").resourceName("parent"),
            span()
                .childOfPrevious()
                .operationName("Connection.query")
                .resourceName("Connection.query")));
  }

  /**
   * Same invariant as {@link #subscriberCallbackInheritsParentSpanFromSubscriptionSite()} but for
   * {@link Single} — guards against drift between the per-type instrumentations.
   */
  @Test
  void singleSubscriberCallbackInheritsParentSpanFromSubscriptionSite()
      throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);

    AgentSpan parent = startSpan("test", "parent");
    AgentScope scope = activateSpan(parent);
    try {
      Single<Connection> connection = Single.create(emitter -> emitter.onSuccess(new Connection()));
      connection.subscribe(
          c -> {
            c.query();
            latch.countDown();
          });
    } finally {
      scope.close();
      parent.finish();
    }

    assertTrue(latch.await(10, TimeUnit.SECONDS), "subscriber callback did not run in time");

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent").resourceName("parent"),
            span()
                .childOfPrevious()
                .operationName("Connection.query")
                .resourceName("Connection.query")));
  }

  /** Test helper that creates a child span when its {@code query()} method is called. */
  static class Connection {
    int query() {
      AgentSpan span = startSpan("test", "Connection.query");
      try {
        return new Random().nextInt();
      } finally {
        span.finish();
      }
    }
  }
}
