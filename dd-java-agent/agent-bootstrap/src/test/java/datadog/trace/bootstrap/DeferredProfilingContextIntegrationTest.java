package datadog.trace.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.EndpointTracker;
import datadog.trace.api.Stateful;
import datadog.trace.api.profiling.ProfilingContextAttribute;
import datadog.trace.api.profiling.ProfilingScope;
import datadog.trace.api.profiling.Timer.TimerType;
import datadog.trace.api.profiling.Timing;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the premain-timing contract of the ddprof profiling context integration: the AppSec-only
 * trigger must not construct it (nor register the process context) on the calling thread, while the
 * profiler-enabled path must keep doing exactly that.
 */
class DeferredProfilingContextIntegrationTest {

  @BeforeEach
  void reset() {
    FakeDatadogProfilingIntegration.constructions.set(0);
    FakeDatadogProfilingIntegration.constructionThread.set(null);
    FakeProcessContext.registrations.set(0);
    FakeProcessContext.registered = new CountDownLatch(1);
    FakeDatadogProfilingIntegration.gate = new CountDownLatch(0);
  }

  @Test
  void deferredConstructionDoesNotRunOnTheCallingThread() throws Exception {
    // hold the deferred construction so the "not done synchronously" assertions cannot race with it
    FakeDatadogProfilingIntegration.gate = new CountDownLatch(1);

    ProfilingContextIntegration integration =
        Agent.createDdprofContextIntegration(fakeProfilingClassLoader(), true);

    // nothing was constructed synchronously on this (premain) thread
    assertNotNull(integration);
    assertEquals(0, FakeDatadogProfilingIntegration.constructions.get());
    assertEquals(0, FakeProcessContext.registrations.get());
    assertEquals("ddprof", integration.name());
    // ... and before the swap the wrapper behaves as a no-op
    assertSame(Stateful.DEFAULT, integration.newScopeState(null));
    assertSame(ProfilingScope.NO_OP, integration.newScope());
    assertSame(Timing.NoOp.INSTANCE, integration.start(TimerType.QUEUEING));

    FakeDatadogProfilingIntegration.gate.countDown();
    assertTrue(
        FakeProcessContext.registered.await(30, TimeUnit.SECONDS),
        "deferred construction never ran");
    // the deferred work happened, and it happened on another thread
    assertEquals(1, FakeDatadogProfilingIntegration.constructions.get());
    assertEquals(1, FakeProcessContext.registrations.get());
    assertNotSame(Thread.currentThread(), FakeDatadogProfilingIntegration.constructionThread.get());
    assertSame(FakeDatadogProfilingIntegration.STATE, integration.newScopeState(null));
  }

  @Test
  void synchronousConstructionKeepsRunningOnTheCallingThread() {
    ProfilingContextIntegration integration =
        Agent.createDdprofContextIntegration(fakeProfilingClassLoader(), false);

    assertTrue(integration instanceof FakeDatadogProfilingIntegration);
    assertEquals(1, FakeDatadogProfilingIntegration.constructions.get());
    assertEquals(1, FakeProcessContext.registrations.get());
    assertSame(Thread.currentThread(), FakeDatadogProfilingIntegration.constructionThread.get());
  }

  @Test
  void delegatesToTheRealIntegrationOnceInitialized() {
    DeferredProfilingContextIntegration deferred =
        new DeferredProfilingContextIntegration("ddprof", FakeDatadogProfilingIntegration::new);

    assertSame(Stateful.DEFAULT, deferred.newScopeState(null));

    deferred.initialize();

    assertSame(FakeDatadogProfilingIntegration.STATE, deferred.newScopeState(null));
    assertEquals("ddprof", deferred.name());
  }

  @Test
  void staysNoOpWhenTheDeferredConstructionFails() {
    DeferredProfilingContextIntegration deferred =
        new DeferredProfilingContextIntegration(
            "ddprof",
            () -> {
              throw new UnsatisfiedLinkError("no native library here");
            });

    deferred.initialize();

    assertSame(Stateful.DEFAULT, deferred.newScopeState(null));
    assertSame(ProfilingScope.NO_OP, deferred.newScope());
    assertSame(ProfilingContextAttribute.NoOp.INSTANCE, deferred.createContextAttribute("tag"));
    assertSame(EndpointTracker.NO_OP, deferred.onRootSpanStarted(null));
    assertEquals(0, deferred.encode("something"));
    assertEquals("ddprof", deferred.name());
  }

  private static ClassLoader fakeProfilingClassLoader() {
    return new ClassLoader(null) {
      @Override
      public Class<?> loadClass(final String name) throws ClassNotFoundException {
        if ("com.datadog.profiling.ddprof.DatadogProfilingIntegration".equals(name)) {
          return FakeDatadogProfilingIntegration.class;
        }
        if ("com.datadog.profiling.agent.ProcessContext".equals(name)) {
          return FakeProcessContext.class;
        }
        return super.loadClass(name);
      }
    };
  }

  public static final class FakeProcessContext {
    static final AtomicInteger registrations = new AtomicInteger();
    static volatile CountDownLatch registered = new CountDownLatch(1);

    public static void register(final ConfigProvider configProvider) {
      registrations.incrementAndGet();
      registered.countDown();
    }
  }

  public static final class FakeDatadogProfilingIntegration implements ProfilingContextIntegration {
    static final Stateful STATE =
        new Stateful() {
          @Override
          public void close() {}

          @Override
          public void activate(final Object context) {}
        };

    static final AtomicInteger constructions = new AtomicInteger();
    static final AtomicReference<Thread> constructionThread = new AtomicReference<>();
    static volatile CountDownLatch gate = new CountDownLatch(0);

    public FakeDatadogProfilingIntegration() {
      try {
        if (!gate.await(30, TimeUnit.SECONDS)) {
          throw new IllegalStateException("construction gate was never released");
        }
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      constructions.incrementAndGet();
      constructionThread.set(Thread.currentThread());
    }

    @Override
    public Stateful newScopeState(final ProfilerContext profilerContext) {
      return STATE;
    }

    @Override
    public String name() {
      return "ddprof";
    }

    @Override
    public void onRootSpanFinished(final AgentSpan rootSpan, final EndpointTracker tracker) {}

    @Override
    public EndpointTracker onRootSpanStarted(final AgentSpan rootSpan) {
      return EndpointTracker.NO_OP;
    }

    @Override
    public Timing start(final TimerType type) {
      return Timing.NoOp.INSTANCE;
    }
  }
}
