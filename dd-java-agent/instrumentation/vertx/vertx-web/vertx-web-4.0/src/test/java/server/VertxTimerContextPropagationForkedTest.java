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
import io.vertx.core.Handler;
import io.vertx.core.TimeoutStream;
import io.vertx.core.Vertx;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
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
      closeVertx(vertx);
    }
  }

  @Test
  void testSetTimerHandlerKeepsContext() throws Exception {
    assertTimerHandlerPropagatesContext((vertx, handler) -> vertx.setTimer(10, handler));
  }

  @Test
  void testTimerStreamHandlerKeepsContext() throws Exception {
    // timerStream(...).handler(...) wraps the user handler in an io.vertx TimeoutStream. The
    // captured timer context must still reach the user callback through that wrapper.
    assertTimerHandlerPropagatesContext((vertx, handler) -> vertx.timerStream(10).handler(handler));
  }

  @Test
  void testPeriodicStreamHandlerKeepsContext() throws Exception {
    // periodicStream(...) uses the same user-facing TimeoutStream wrapper as timerStream(...). It
    // fires repeatedly, so cancel after the first delivery to keep a single asyncChild span.
    assertTimerHandlerPropagatesContext(
        (vertx, handler) -> {
          TimeoutStream stream = vertx.periodicStream(10);
          stream.handler(
              id -> {
                stream.cancel();
                handler.handle(id);
              });
        });
  }

  private void assertTimerHandlerPropagatesContext(BiConsumer<Vertx, Handler<Long>> scheduler)
      throws Exception {
    Vertx vertx = Vertx.vertx();
    CountDownLatch finished = new CountDownLatch(1);
    AtomicBoolean sawActiveSpan = new AtomicBoolean();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AgentSpan parent = startSpan("test", "parent");

    try {
      try (AgentScope ignored = activateSpan(parent)) {
        scheduler.accept(
            vertx,
            id -> {
              try {
                sawActiveSpan.set(activeSpan() != null);
                asyncChild();
              } catch (Throwable t) {
                failure.set(t);
              } finally {
                finished.countDown();
              }
            });
      } finally {
        parent.finish();
      }

      assertTrue(finished.await(5, SECONDS));
      if (failure.get() != null) {
        throw new AssertionError(failure.get());
      }
      assertTrue(sawActiveSpan.get());

      assertTraces(
          trace(
              SORT_BY_START_TIME,
              span().root().operationName("parent"),
              span().childOfPrevious().operationName("asyncChild")));
    } finally {
      closeVertx(vertx);
    }
  }

  private static void closeVertx(Vertx vertx) throws InterruptedException {
    // Vert.x 4.0 predates Future#await; close via a callback and wait on a latch.
    CountDownLatch closed = new CountDownLatch(1);
    vertx.close(result -> closed.countDown());
    assertTrue(closed.await(5, SECONDS));
  }

  @Trace(operationName = "asyncChild")
  private static void asyncChild() {}
}
