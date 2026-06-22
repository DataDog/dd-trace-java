package testdog.trace.instrumentation.lambda;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.Trace;
import datadog.trace.bootstrap.FieldBackedContextAccessor;
import datadog.trace.bootstrap.instrumentation.java.concurrent.RunnableWrapper;
import datadog.trace.junit.utils.config.WithConfig;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test that {@code InnerClassLambdaMetafactory} instrumentation makes lambda {@code
 * Runnable}s field-injectable (so the agent no longer needs to wrap them) and propagates context
 * across an executor. Must live outside {@code datadog.*} so the agent does not ignore the test
 * class and its lambdas.
 */
@WithConfig(key = "trace.lambda.enabled", value = "true")
public class LambdaMetafactoryIntegrationTest extends AbstractInstrumentationTest {

  @Test
  void lambdaRunnableIsFieldInjectedNotWrapped() {
    // Created after the agent is installed in @BeforeAll, so its linkage goes through the
    // instrumented metafactory.
    Runnable lambda = () -> {};

    assertTrue(
        lambda instanceof FieldBackedContextAccessor,
        "lambda Runnable should be field-injected via the metafactory instrumentation");
    // because it is field-injected, wrapping must be skipped and identity preserved
    assertSame(lambda, RunnableWrapper.wrapIfNeeded(lambda));
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
