package datadog.trace.civisibility.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.civisibility.DDTest;
import datadog.trace.api.civisibility.DDTestSuite;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.domain.TestContext;
import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.api.civisibility.telemetry.NoOpMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.SkipReason;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.domain.TestFrameworkModule;
import datadog.trace.civisibility.domain.TestFrameworkSession;
import datadog.trace.civisibility.domain.TestImpl;
import datadog.trace.civisibility.domain.TestSuiteImpl;
import datadog.trace.civisibility.execution.Regular;
import datadog.trace.civisibility.utils.StrongMapContextStore;
import java.util.Collections;
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

  @Test
  void excludedAttemptToFixAssignmentDoesNotProduceCompletionResult() {
    TestFrameworkSession session = mock(TestFrameworkSession.class);
    TestFrameworkModule module = mock(TestFrameworkModule.class);
    TestSuiteImpl suite = mock(TestSuiteImpl.class);
    TestImpl test = mock(TestImpl.class);
    TestIdentifier id = new TestIdentifier("suite", "test", null);
    when(session.testModuleStart("module", null)).thenReturn(module);
    when(module.testSuiteStart("suite", null, null, false, TestFrameworkInstrumentation.JUNIT4))
        .thenReturn(suite);
    when(suite.testStart("test", null, null, null)).thenReturn(test);
    when(test.getIdentifier()).thenReturn(id);
    when(test.getContext()).thenReturn(mock(TestContext.class));
    when(test.getStatus()).thenReturn(TestStatus.fail);
    when(module.isAttemptToFix(id)).thenReturn(true);
    TestEventsHandlerImpl<Object, Object> handler = handler(() -> session);
    Object suiteKey = new Object();
    Object testKey = new Object();
    handler.onTestSuiteStart(
        suiteKey,
        "suite",
        null,
        null,
        null,
        null,
        false,
        TestFrameworkInstrumentation.JUNIT4,
        null);
    handler.onTestStart(
        suiteKey,
        testKey,
        "test",
        null,
        null,
        null,
        Collections.emptyList(),
        TestSourceData.UNKNOWN,
        null,
        Regular.INSTANCE);
    handler.onTestFinish(testKey, null, Regular.INSTANCE);
    verify(test, never()).setTag(eq(Tags.TEST_TEST_MANAGEMENT_ATTEMPT_TO_FIX_PASSED), any());
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
