package server;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.Trace;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.vertx.core.Vertx;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class VertxTimerContextPropagationForkedTest extends AbstractInstrumentationTest {
  @Test
  void testTimerCallbackCanPropagateContextToNestedTimer() throws Exception {
    Vertx vertx = Vertx.vertx();
    CountDownLatch nestedTimerFinished = new CountDownLatch(1);
    AtomicBoolean firstTimerSawActiveSpan = new AtomicBoolean();
    AtomicBoolean nestedTimerSawActiveSpan = new AtomicBoolean();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AgentSpan parent = startSpan("test", "parent");

    try {
      try (AgentScope ignored = activateSpan(parent)) {
        vertx.setTimer(
            10,
            firstTimerId -> {
              try {
                firstTimerSawActiveSpan.set(activeSpan() != null);

                // A timer callback is user code. Async propagation must stay enabled here so work
                // scheduled from the callback can keep the captured timer context.
                vertx.setTimer(
                    10,
                    nestedTimerId -> {
                      try {
                        nestedTimerSawActiveSpan.set(activeSpan() != null);
                        asyncChild();
                      } catch (Throwable t) {
                        failure.set(t);
                      } finally {
                        nestedTimerFinished.countDown();
                      }
                    });
              } catch (Throwable t) {
                failure.set(t);
                nestedTimerFinished.countDown();
              }
            });
      } finally {
        parent.finish();
      }

      assertTrue(nestedTimerFinished.await(5, SECONDS));
      if (failure.get() != null) {
        throw new AssertionError(failure.get());
      }
      assertTrue(firstTimerSawActiveSpan.get());
      assertTrue(nestedTimerSawActiveSpan.get());

      assertTraces(
          trace(
              SORT_BY_START_TIME,
              span().root().operationName("parent"),
              span().childOfPrevious().operationName("asyncChild")));
    } finally {
      vertx.close().await(5, SECONDS);
    }
  }

  @Trace(operationName = "asyncChild")
  private static void asyncChild() {}
}
