package testdog.trace.instrumentation.lambda;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.Trace;
import datadog.trace.bootstrap.FieldBackedContextAccessor;
import datadog.trace.bootstrap.instrumentation.java.concurrent.RunnableWrapper;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Lambda integration tests outside the ignored {@code datadog.*} prefix. */
@WithConfig(key = "trace.lambda.enabled", value = "true")
public class LambdaMetafactoryIntegrationTest extends AbstractInstrumentationTest {

  @Test
  void lambdaRunnableIsFieldInjectedNotWrapped() {
    // Link after the agent is installed.
    Runnable lambda = () -> {};

    assertTrue(
        lambda instanceof FieldBackedContextAccessor,
        "lambda Runnable should be field-injected via the metafactory instrumentation");
    assertSame(lambda, RunnableWrapper.wrapIfNeeded(lambda));
  }

  @Test
  void sameOwnerRunnableCaptureShapesAreAllFieldInjected() {
    AtomicInteger counter = new AtomicInteger();
    int delta = 7;
    Runnable[] lambdas = {() -> {}, counter::incrementAndGet, () -> counter.addAndGet(delta)};

    for (Runnable lambda : lambdas) {
      assertTrue(
          lambda instanceof FieldBackedContextAccessor,
          "every Runnable lambda shape should be field-injected");
      assertSame(lambda, RunnableWrapper.wrapIfNeeded(lambda));
      lambda.run();
    }
    assertEquals(8, counter.get());
  }

  @Test
  void nonRunnableLambdaIsNotTransformed() {
    Supplier<Object> lambda = Object::new;

    assertFalse(
        lambda instanceof FieldBackedContextAccessor,
        "non-Runnable lambda should bypass the agent transformer");
  }

  @Test
  void lambdaPropagatesContextAcrossExecutor() throws Exception {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      CountDownLatch latch = new CountDownLatch(1);
      submitUnderParent(pool, latch);
      assertTrue(latch.await(10, TimeUnit.SECONDS), "child task did not run");

      assertTraces(
          trace(
              SORT_BY_START_TIME,
              span().root().operationName("parent"),
              span().childOfPrevious().operationName("lambda-child")));
    } finally {
      pool.shutdownNow();
    }
  }

  @Trace(operationName = "parent")
  void submitUnderParent(ExecutorService pool, CountDownLatch latch) {
    pool.execute(
        () -> {
          child();
          latch.countDown();
        });
  }

  @Trace(operationName = "lambda-child")
  void child() {}
}
