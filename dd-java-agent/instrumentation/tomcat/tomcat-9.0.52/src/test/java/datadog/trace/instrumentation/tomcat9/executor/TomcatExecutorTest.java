package datadog.trace.instrumentation.tomcat9.executor;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import java.util.concurrent.CountDownLatch;
import org.apache.tomcat.util.threads.TaskQueue;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;

class TomcatExecutorTest extends AbstractInstrumentationTest {

  @Test
  void propagatesContextAcrossExecuteAndSubmit() {
    // Verify that the dependency exercises Tomcat's fork.
    //noinspection ConstantValue
    assertFalse(
        java.util.concurrent.ThreadPoolExecutor.class.isAssignableFrom(ThreadPoolExecutor.class));
    ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 5, SECONDS, new TaskQueue());
    CountDownLatch executed = new CountDownLatch(1);
    try {
      runUnderTrace(
          "parent",
          () -> {
            executor.execute(
                () -> {
                  runUnderTrace("execute-child", () -> null);
                  executed.countDown();
                });
            assertTrue(executed.await(5, SECONDS));
            executor
                .submit((Runnable) () -> runUnderTrace("submit-child", () -> null))
                .get(5, SECONDS);
            return null;
          });

      assertTraces(
          trace(
              SORT_BY_START_TIME,
              span().root().operationName("parent"),
              span().childOfIndex(0).operationName("execute-child"),
              span().childOfIndex(0).operationName("submit-child")));
    } finally {
      executor.shutdown();
    }
  }
}
