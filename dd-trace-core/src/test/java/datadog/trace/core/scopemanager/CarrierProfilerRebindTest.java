package datadog.trace.core.scopemanager;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.context.ContextScope;
import datadog.trace.api.EndpointTracker;
import datadog.trace.api.Stateful;
import datadog.trace.api.profiling.ProfilingContextAttribute;
import datadog.trace.api.profiling.ProfilingScope;
import datadog.trace.api.profiling.Timer.TimerType;
import datadog.trace.api.profiling.Timing;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CarrierProfilerRebindTest {

  static final CoreTracer TRACER = CoreTracer.builder().build();

  /** Records activate/close counts so the test can observe carrier rebind/unbind. */
  static final class CountingProfiling implements ProfilingContextIntegration {
    final AtomicInteger activations = new AtomicInteger();
    final AtomicInteger closes = new AtomicInteger();
    private final boolean carrierBound;

    CountingProfiling(boolean carrierBound) {
      this.carrierBound = carrierBound;
    }

    @Override
    public boolean isCarrierThreadBound() {
      return carrierBound;
    }

    @Override
    public Stateful newScopeState(ProfilerContext profilerContext) {
      return new Stateful() {
        @Override
        public void activate(Object context) {
          activations.incrementAndGet();
        }

        @Override
        public void close() {
          closes.incrementAndGet();
        }
      };
    }

    @Override
    public String name() {
      return "counting";
    }

    @Override
    public ProfilingContextAttribute createContextAttribute(String attribute) {
      return ProfilingContextAttribute.NoOp.INSTANCE;
    }

    @Override
    public ProfilingScope newScope() {
      return ProfilingScope.NO_OP;
    }

    @Override
    public void onRootSpanFinished(AgentSpan rootSpan, EndpointTracker tracker) {}

    @Override
    public EndpointTracker onRootSpanStarted(AgentSpan rootSpan) {
      return EndpointTracker.NO_OP;
    }

    @Override
    public Timing start(TimerType type) {
      return Timing.NoOp.INSTANCE;
    }
  }

  @Test
  void rebindAndUnbindDriveProfilerWhenCarrierBound() {
    CountingProfiling profiling = new CountingProfiling(true);
    ContinuableScopeManager manager =
        new ContinuableScopeManager(0, false, profiling, HealthMetrics.NO_OP);
    AgentSpan span = TRACER.startSpan("test", "op");
    try (ContextScope scope = manager.attach(span)) {
      // attach already activated the profiler once; measure only rebind/unbind from here.
      profiling.activations.set(0);
      profiling.closes.set(0);

      manager.rebindProfilingContextToCarrier();
      manager.unbindProfilingContextFromCarrier();

      assertEquals(1, profiling.activations.get(), "rebind should re-apply profiler context");
      assertEquals(1, profiling.closes.get(), "unbind should clear profiler context");
    } finally {
      span.finish();
    }
  }

  @Test
  void rebindAndUnbindAreNoOpWhenNotCarrierBound() {
    CountingProfiling profiling = new CountingProfiling(false);
    ContinuableScopeManager manager =
        new ContinuableScopeManager(0, false, profiling, HealthMetrics.NO_OP);
    AgentSpan span = TRACER.startSpan("test", "op");
    try (ContextScope scope = manager.attach(span)) {
      profiling.activations.set(0);
      profiling.closes.set(0);

      manager.rebindProfilingContextToCarrier();
      manager.unbindProfilingContextFromCarrier();

      assertEquals(0, profiling.activations.get(), "no rebind when not carrier bound");
      assertEquals(0, profiling.closes.get(), "no unbind when not carrier bound");
    } finally {
      span.finish();
    }
  }
}
