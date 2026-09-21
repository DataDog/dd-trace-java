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
    FakeDatadogProfilingIntegration.onAttachCalls.set(0);
    FakeDatadogProfilingIntegration.onDetachCalls.set(0);
    FakeDatadogProfilingIntegration.onRootSpanFinishedCalls.set(0);
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

    // every other pass-through method must reach the swapped-in delegate too, not just
    // newScopeState/name — each is a distinct code path in DeferredProfilingContextIntegration.
    deferred.onAttach();
    deferred.onDetach();
    assertEquals(1, FakeDatadogProfilingIntegration.onAttachCalls.get());
    assertEquals(1, FakeDatadogProfilingIntegration.onDetachCalls.get());
    assertEquals(42, deferred.encodeOperationName("op"));
    assertEquals(43, deferred.encodeResourceName("resource"));
    deferred.onRootSpanFinished(null, EndpointTracker.NO_OP);
    assertEquals(1, FakeDatadogProfilingIntegration.onRootSpanFinishedCalls.get());
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

  @Test
  void availabilityCallbacksRunOnlyOnceTheRealIntegrationIsIn() {
    DeferredProfilingContextIntegration deferred =
        new DeferredProfilingContextIntegration("ddprof", FakeDatadogProfilingIntegration::new);
    AtomicInteger callbacks = new AtomicInteger();

    deferred.whenAvailable(callbacks::incrementAndGet);
    assertEquals(0, callbacks.get());

    deferred.initialize();
    assertEquals(1, callbacks.get());

    // registering after the swap runs the callback straight away, without waiting for anything
    deferred.whenAvailable(callbacks::incrementAndGet);
    assertEquals(2, callbacks.get());
  }

  @Test
  void availabilityCallbacksNeverRunWhenTheDeferredConstructionFails() {
    DeferredProfilingContextIntegration deferred =
        new DeferredProfilingContextIntegration(
            "ddprof",
            () -> {
              throw new UnsatisfiedLinkError("no native library here");
            });
    AtomicInteger callbacks = new AtomicInteger();

    deferred.whenAvailable(callbacks::incrementAndGet);
    deferred.initialize();

    assertEquals(0, callbacks.get());
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
    static final AtomicInteger onAttachCalls = new AtomicInteger();
    static final AtomicInteger onDetachCalls = new AtomicInteger();
    static final AtomicInteger onRootSpanFinishedCalls = new AtomicInteger();

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
    public void onRootSpanFinished(final AgentSpan rootSpan, final EndpointTracker tracker) {
      onRootSpanFinishedCalls.incrementAndGet();
    }

    @Override
    public EndpointTracker onRootSpanStarted(final AgentSpan rootSpan) {
      return EndpointTracker.NO_OP;
    }

    @Override
    public Timing start(final TimerType type) {
      return Timing.NoOp.INSTANCE;
    }

    @Override
    public void onAttach() {
      onAttachCalls.incrementAndGet();
    }

    @Override
    public void onDetach() {
      onDetachCalls.incrementAndGet();
    }

    @Override
    public int encodeOperationName(final CharSequence constant) {
      return 42;
    }

    @Override
    public int encodeResourceName(final CharSequence constant) {
      return 43;
    }
  }
}
