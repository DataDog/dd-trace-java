package datadog.trace.civisibility.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.civisibility.DDTest;
import datadog.trace.api.civisibility.DDTestSuite;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.telemetry.NoOpMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.SkipReason;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.civisibility.domain.TestFrameworkModule;
import datadog.trace.civisibility.domain.TestFrameworkSession;
import datadog.trace.civisibility.utils.StrongMapContextStore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TestEventsHandlerImplTest {

  @Test
  void doesNotCreateSessionWhenUnused() {
    AtomicInteger creations = new AtomicInteger();
    TestEventsHandlerImpl<Object, Object> handler =
        handler(
            () -> {
              creations.incrementAndGet();
              return mock(TestFrameworkSession.class);
            });

    handler.close();

    assertEquals(0, creations.get());
  }

  @Test
  void createsSessionAndModuleOnceAndClosesThem() {
    TestFrameworkSession session = mock(TestFrameworkSession.class);
    TestFrameworkModule module = mock(TestFrameworkModule.class);
    when(session.testModuleStart("module", null)).thenReturn(module);
    TestIdentifier test = new TestIdentifier("suite", "test", null);
    when(module.skipReason(test)).thenReturn(SkipReason.ITR);
    AtomicInteger creations = new AtomicInteger();
    TestEventsHandlerImpl<Object, Object> handler =
        handler(
            () -> {
              creations.incrementAndGet();
              return session;
            });

    assertSame(SkipReason.ITR, handler.skipReason(test));
    assertSame(SkipReason.ITR, handler.skipReason(test));
    handler.close();

    assertEquals(1, creations.get());
    verify(session).testModuleStart("module", null);
    verify(module).end(null);
    verify(session).end(null);
  }

  @Test
  void createsSessionImmediatelyWhenRequested() {
    TestFrameworkSession session = mock(TestFrameworkSession.class);
    TestFrameworkModule module = mock(TestFrameworkModule.class);
    when(session.testModuleStart("module", null)).thenReturn(module);
    AtomicInteger creations = new AtomicInteger();

    TestEventsHandlerImpl<Object, Object> handler =
        handler(
            () -> {
              creations.incrementAndGet();
              return session;
            },
            true);

    assertEquals(1, creations.get());
    handler.close();
    verify(module).end(null);
    verify(session).end(null);
  }

  private static TestEventsHandlerImpl<Object, Object> handler(
      Supplier<TestFrameworkSession> testSessionSupplier) {
    return handler(testSessionSupplier, false);
  }

  private static TestEventsHandlerImpl<Object, Object> handler(
      Supplier<TestFrameworkSession> testSessionSupplier, boolean eagerSessionStart) {
    ContextStore<Object, DDTestSuite> suiteStore = new StrongMapContextStore<>();
    ContextStore<Object, DDTest> testStore = new StrongMapContextStore<>();
    return new TestEventsHandlerImpl<>(
        NoOpMetricCollector.INSTANCE,
        testSessionSupplier,
        "module",
        eagerSessionStart,
        suiteStore,
        testStore);
  }
}
