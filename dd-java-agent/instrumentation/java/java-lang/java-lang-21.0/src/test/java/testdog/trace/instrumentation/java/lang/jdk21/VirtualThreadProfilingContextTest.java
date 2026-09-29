package testdog.trace.instrumentation.java.lang.jdk21;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import datadog.context.Context;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.TestProfilingContextIntegration;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.IdGenerationStrategy;
import datadog.trace.api.Stateful;
import datadog.trace.api.Trace;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.java.lang.VirtualThreadState;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests profiler context binding during virtual-thread lifecycle transitions. */
public class VirtualThreadProfilingContextTest extends AbstractInstrumentationTest {
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final RecordingProfilingContextIntegration PROFILING_CONTEXT =
      new RecordingProfilingContextIntegration();

  @BeforeAll
  static void installRecordingProfilingContext() {
    tracer.close();
    writer = new ListWriter();
    CoreTracer coreTracer =
        CoreTracer.builder()
            .writer(writer)
            .idGenerationStrategy(IdGenerationStrategy.fromName("SEQUENTIAL"))
            .profilingContextIntegration(PROFILING_CONTEXT)
            .build();
    TracerInstaller.forceInstallGlobalTracer(coreTracer);
    tracer = coreTracer;
  }

  @BeforeEach
  void resetProfilingContext() {
    PROFILING_CONTEXT.reset();
  }

  @Test
  void testCapturedContextIsBoundBeforeFirstRunnableInvocation() {
    assumeFalse(VirtualThreadState.usePerMountContext(), "requires the JDK 22+ context path");
    assertSame(PROFILING_CONTEXT, AgentTracer.get().getProfilingContext());

    AtomicLong expectedSpanId = new AtomicLong();
    AtomicLong spanIdAtRunnableEntry = new AtomicLong();
    AtomicReference<List<Long>> bindingsAtRunnableEntry = new AtomicReference<>();

    runWithSpan(expectedSpanId, spanIdAtRunnableEntry, bindingsAtRunnableEntry);

    List<Long> bindings = bindingsAtRunnableEntry.get();
    assertTrue(bindings.size() >= 2);
    assertEquals(
        List.of(0L, expectedSpanId.get()),
        bindings.subList(bindings.size() - 2, bindings.size()),
        "the first mount should clear the carrier before run() binds the captured span");
    assertEquals(
        expectedSpanId.get(),
        spanIdAtRunnableEntry.get(),
        "the captured span must be bound before the no-park runnable starts");
  }

  @Trace(operationName = "parent")
  private static void runWithSpan(
      AtomicLong expectedSpanId,
      AtomicLong spanIdAtRunnableEntry,
      AtomicReference<List<Long>> bindingsAtRunnableEntry) {
    expectedSpanId.set(AgentTracer.activeSpan().getSpanId());
    PROFILING_CONTEXT.reset();

    Thread thread =
        Thread.startVirtualThread(
            () -> {
              spanIdAtRunnableEntry.set(PROFILING_CONTEXT.activeSpanId());
              bindingsAtRunnableEntry.set(PROFILING_CONTEXT.bindings());
            });
    try {
      assertTrue(thread.join(TIMEOUT), "virtual thread did not finish in time");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  private static final class RecordingProfilingContextIntegration
      extends TestProfilingContextIntegration {
    private final AtomicLong activeSpanId = new AtomicLong();
    private final List<Long> bindings = new ArrayList<>();
    private final Stateful contextManager =
        new Stateful() {
          @Override
          public void close() {
            record(0);
          }

          @Override
          public void activate(Object context) {
            record(((ProfilerContext) context).getSpanId());
          }
        };

    @Override
    public Stateful newScopeState(ProfilerContext profilerContext) {
      return contextManager;
    }

    @Override
    public void setContext(Context context) {
      AgentSpan span = AgentSpan.fromContext(context);
      if (span == null) {
        contextManager.close();
      } else {
        contextManager.activate(span.spanContext());
      }
    }

    @Override
    public boolean isThreadContextBindingRequired() {
      return true;
    }

    synchronized void reset() {
      activeSpanId.set(0);
      bindings.clear();
    }

    long activeSpanId() {
      return activeSpanId.get();
    }

    synchronized List<Long> bindings() {
      return List.copyOf(bindings);
    }

    private synchronized void record(long spanId) {
      activeSpanId.set(spanId);
      bindings.add(spanId);
    }
  }
}
