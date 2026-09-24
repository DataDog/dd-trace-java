import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_QUEUEING_TIME_ENABLED;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.concurrent.Executors.defaultThreadFactory;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.TestProfilingContextIntegration;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.jfr.InstrumentationBasedProfiling;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.test.junit.utils.config.WithConfig;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@WithConfig(key = PROFILING_ENABLED, value = "true")
@WithConfig(key = PROFILING_QUEUEING_TIME_ENABLED, value = "true")
class TimingReadinessForkedTest extends AbstractInstrumentationTest {
  private static TestProfilingContextIntegration profiling;

  @BeforeAll
  static void installProfilingTracer() {
    tracer.close();
    writer = new ListWriter();
    profiling = new TestProfilingContextIntegration();
    CoreTracer profilingTracer =
        CoreTracer.builder().writer(writer).profilingContextIntegration(profiling).build();
    TracerInstaller.forceInstallGlobalTracer(profilingTracer);
    tracer = profilingTracer;
  }

  @Test
  void skipsQueueMetadataUntilJfrIsReadyWhilePropagatingTrace() throws Exception {
    TestExecutor executor = new TestExecutor();
    try {
      assertFalse(InstrumentationBasedProfiling.isJFRReady());
      submitTask(executor);

      assertEquals(0, executor.countingQueue.sizeCalls.get());
      assertTrue(profiling.getClosedTimings().isEmpty());

      InstrumentationBasedProfiling.enableInstrumentationBasedProfiling();
      submitTask(executor);

      assertTrue(executor.countingQueue.sizeCalls.get() > 0);
      assertTrue(profiling.isBalanced());
      assertFalse(profiling.getClosedTimings().isEmpty());
    } finally {
      executor.shutdownGracefully(0, 5, SECONDS).get(10, SECONDS);
      profiling.getClosedTimings().clear();
    }
  }

  private static void submitTask(TestExecutor executor) throws Exception {
    AgentSpan span = startSpan("test", "submit");
    try (ContextScope scope = activateSpan(span)) {
      ContextCheckingTask task = new ContextCheckingTask();
      executor.submit(task).get(5, SECONDS);
      assertEquals(span.getSpanId(), task.spanId);
    } finally {
      span.finish();
    }
  }

  static class ContextCheckingTask implements Runnable {
    volatile long spanId;

    @Override
    public void run() {
      AgentSpan span = AgentTracer.activeSpan();
      spanId = span == null ? 0 : span.getSpanId();
    }
  }

  static class TestExecutor extends SingleThreadEventExecutor {
    CountingQueue countingQueue;

    TestExecutor() {
      super(null, defaultThreadFactory(), true);
    }

    @Override
    protected Queue<Runnable> newTaskQueue() {
      countingQueue = new CountingQueue();
      return countingQueue;
    }

    // Newer Netty versions use the factory overload with a capacity argument.
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
      return newTaskQueue();
    }

    @Override
    protected void run() {
      while (true) {
        Runnable task = takeTask();
        if (task != null) {
          task.run();
          updateLastExecutionTime();
        }
        if (confirmShutdown()) {
          return;
        }
      }
    }
  }

  static class CountingQueue extends LinkedBlockingQueue<Runnable> {
    final AtomicInteger sizeCalls = new AtomicInteger();

    @Override
    public int size() {
      sizeCalls.incrementAndGet();
      return super.size();
    }

    @Override
    public boolean isEmpty() {
      // Keep executor housekeeping separate from metadata reads.
      return super.size() == 0;
    }
  }
}
