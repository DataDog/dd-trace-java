package testdog.trace.instrumentation.rxjava3;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.reactivex.rxjava3.core.Maybe;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class SubscriptionTest extends AbstractInstrumentationTest {

  @Test
  void subscriptionPropagatesParentSpan() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);

    AgentSpan parent = startSpan("test", "parent");
    try (AgentScope scope = activateSpan(parent)) {
      Maybe<Connection> connection = Maybe.create(emitter -> emitter.onSuccess(new Connection()));
      connection.subscribe(
          c -> {
            c.query();
            latch.countDown();
          });
    } finally {
      parent.finish();
    }
    latch.await();

    assertTraces(
        trace(
            SORT_BY_START_TIME,
            span().root().operationName("parent"),
            span().childOfPrevious().operationName("Connection.query")));
  }

  static class Connection {
    int query() {
      AgentSpan span = startSpan("test", "Connection.query");
      span.finish();
      return new Random().nextInt();
    }
  }
}
