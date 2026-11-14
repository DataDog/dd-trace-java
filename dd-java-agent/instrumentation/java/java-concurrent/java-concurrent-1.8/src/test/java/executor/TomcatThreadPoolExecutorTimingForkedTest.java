package executor;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_QUEUEING_TIME_ENABLED;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static executor.TomcatThreadPoolExecutorTest.dispatch;
import static executor.TomcatThreadPoolExecutorTest.hasTimedExecute;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.TestProfilingContextIntegration;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.jfr.InstrumentationBasedProfiling;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.test.junit.utils.config.WithConfig;
import executor.TomcatThreadPoolExecutorTest.CheckingTask;
import executor.TomcatThreadPoolExecutorTest.Pool;
import executor.TomcatThreadPoolExecutorTest.RetryQueue;
import org.apache.tomcat.util.threads.TaskQueue;
import org.junit.jupiter.api.BeforeAll;
import org.tabletest.junit.TableTest;

@WithConfig(key = PROFILING_ENABLED, value = "true")
@WithConfig(key = PROFILING_QUEUEING_TIME_ENABLED, value = "true")
class TomcatThreadPoolExecutorTimingForkedTest extends AbstractInstrumentationTest {
  private static TestProfilingContextIntegration profiling;

  @BeforeAll
  static void installProfilingTracer() {
    tracer.close();
    writer = new ListWriter();
    profiling = new TestProfilingContextIntegration();
    CoreTracer profilingTracer =
        CoreTracer.builder()
            .writer(writer)
            .strictTraceWrites(true)
            .profilingContextIntegration(profiling)
            .build();
    TracerInstaller.forceInstallGlobalTracer(profilingTracer);
    tracer = profilingTracer;
    InstrumentationBasedProfiling.enableInstrumentationBasedProfiling();
  }

  @TableTest({
    "Operation       | retry",
    "execute         | false",
    "submit runnable | false",
    "timed execute   | false",
    "execute         | true ",
    "timed execute   | true "
  })
  void timesQueuedTaskOnce(String operation, boolean retry) throws Exception {
    TomcatThreadPoolExecutorTest.assumeFork();
    if (operation.equals("timed execute")) assumeTrue(hasTimedExecute());
    profiling.getClosedTimings().clear();
    try (Pool pool = new Pool(retry ? new RetryQueue() : new TaskQueue(), false)) {
      pool.block();
      CheckingTask task = new CheckingTask();
      AgentSpan parent = startSpan("test", "parent");
      try (ContextScope ignored = activateSpan(parent)) {
        dispatch(pool.executor, task, operation);
      } finally {
        parent.finish();
      }
      assertEquals(1, pool.executor.getQueue().size());
      assertTrue(profiling.getClosedTimings().isEmpty());
      pool.proceed.countDown();
      assertTrue(task.done.await(5, SECONDS));
      assertSame(parent, task.observed);
      assertTraces(
          trace(
              SORT_BY_START_TIME,
              span().root().operationName("parent"),
              span().childOfPrevious().operationName("child")));
      assertEquals(1, profiling.getClosedTimings().size());
      assertTrue(profiling.isBalanced());
    }
  }
}
