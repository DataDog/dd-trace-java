package datadog.trace.civisibility.domain;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import datadog.context.Context;
import datadog.context.ContextHandle;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.DDTest;
import datadog.trace.api.civisibility.DDTestSuite;
import datadog.trace.api.civisibility.InstrumentationTestBridge;
import datadog.trace.api.civisibility.coverage.CoveragePerTestBridge;
import datadog.trace.api.civisibility.coverage.CoverageProbes;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.coverage.NoOpCoverageStore;
import datadog.trace.api.civisibility.domain.TestContext;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.civisibility.codeowners.NoCodeowners;
import datadog.trace.civisibility.config.ConfigurationErrors;
import datadog.trace.civisibility.decorator.TestDecoratorImpl;
import datadog.trace.civisibility.events.TestEventsHandlerImpl;
import datadog.trace.civisibility.source.LinesResolver;
import datadog.trace.civisibility.source.NoOpSourcePathResolver;
import datadog.trace.civisibility.test.ExecutionResults;
import datadog.trace.civisibility.utils.StrongMapContextStore;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

@WithConfig(key = "legacy.context-manager.enabled", value = "false")
class ContextRestorationTest extends DDJavaSpecification {
  private CoreTracer tracer;
  private AgentTracer.TracerAPI previousTracer;
  private ContextHandle previousContext;
  private AgentSpan moduleSpan;

  @BeforeEach
  void setUpTracer() {
    previousTracer = AgentTracer.get();
    tracer = CoreTracer.builder().writer(new ListWriter()).build();
    AgentTracer.forceRegister(tracer);
    // The current tracer still uses the Context API shim.
    AgentTracer.installLegacyContextManager();
    previousContext = Context.current().checkpoint();
    moduleSpan = tracer.startSpan("test", "module");
  }

  @AfterEach
  void restoreTracer() {
    previousContext.swap();
    moduleSpan.finish();
    tracer.close();
    AgentTracer.forceRegister(previousTracer);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testsAndSuitesRestoreTheEnclosingContext(boolean parallelized) {
    moduleSpan.swap();
    Context parent = Context.current();
    Context.setCaptureEnabled(false);
    TestSuiteImpl suite = suite(parallelized);
    Context suiteContext = Context.current();
    TestImpl outer = suite.testStart("outer", null, null);
    Context outerContext = Context.current();
    TestImpl inner = suite.testStart("inner", null, null);
    inner.end(null);
    assertSame(outerContext, Context.current());
    assertDoesNotThrow(() -> inner.end(null));
    outer.end(null);
    assertSame(suiteContext, Context.current());
    suite.end(null);
    assertSame(parent, Context.current());
    assertFalse(Context.isCaptureEnabled());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void endingATestDiscardsOutstandingContextsInOneRestore(boolean parallelized) {
    TestSuiteImpl suite = suite(parallelized);
    Context suiteContext = Context.current();
    TestImpl test = suite.testStart("test", null, null);
    DDSpan testSpan = (DDSpan) AgentTracer.activeSpan();
    DDSpan first = (DDSpan) tracer.startSpan("test", "first");
    first.swap();
    DDSpan second = (DDSpan) tracer.startSpan("test", "second");
    second.swap();
    try {
      test.end(null);
      assertSame(suiteContext, Context.current());
      assertTrue(testSpan.isFinished());
      assertFalse(first.isFinished());
      assertFalse(second.isFinished());
    } finally {
      second.finish();
      first.finish();
      test.end(null);
      suite.end(null);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void listenerFailureRestoresTheEnclosingTestAndFinishesTheInnerTest(boolean parallelized) {
    TestSuiteImpl suite = suite(parallelized);
    Context suiteContext = Context.current();
    TestImpl outer = suite.testStart("outer", null, null);
    Context enclosingContext = Context.current();
    TestImpl test = suite.testStart("listener-failure", null, null);
    DDSpan testSpan = (DDSpan) AgentTracer.activeSpan();
    CoverageProbes probes = mock(CoverageProbes.class);
    CoveragePerTestBridge.setThreadLocalCoverageProbes(probes);
    RuntimeException failure = new RuntimeException("browser command failed");
    AtomicReference<TestContext> failingContext = new AtomicReference<>(test.getContext());
    InstrumentationTestBridge.registerListener(context -> {
      if (failingContext.compareAndSet(context, null)) {
        throw failure;
      }
    });
    ContextStore<Object, DDTest> tests = new StrongMapContextStore<>();
    Object testKey = new Object();
    tests.put(testKey, test);
    TestEventsHandlerImpl<Object, Object> handler = handler(new StrongMapContextStore<>(), tests);
    try {
      assertSame(
          failure,
          assertThrows(RuntimeException.class, () -> handler.onTestFinish(testKey, null, null)));

      assertNull(tests.get(testKey));
      assertTrue(testSpan.isFinished());
      assertSame(enclosingContext, Context.current());
      CoveragePerTestBridge.recordCoverage(getClass());
      verifyNoInteractions(probes);
      assertDoesNotThrow(() -> test.end(null));
    } finally {
      failingContext.set(null);
      outer.end(null);
      assertSame(suiteContext, Context.current());
      suite.end(null);
      handler.close();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void coverageFailureStillRestoresContextAndFinishesTheTest(boolean parallelized) {
    RuntimeException failure = new RuntimeException("coverage report failed");
    CoverageStore store = mock(CoverageStore.class);
    when(store.report(any(), any(), anyLong())).thenThrow(failure);
    CoverageStore.Factory factory = mock(CoverageStore.Factory.class);
    when(factory.create(any())).thenReturn(store);
    TestSuiteImpl suite = suite(parallelized, SpanTagsPropagator.NOOP_PROPAGATOR, factory);
    Context suiteContext = Context.current();
    TestImpl test = suite.testStart("coverage-failure", null, null);
    DDSpan testSpan = (DDSpan) AgentTracer.activeSpan();

    assertSame(failure, assertThrows(RuntimeException.class, () -> test.end(null)));

    assertTrue(testSpan.isFinished());
    assertSame(suiteContext, Context.current());
    suite.end(null);
  }

  @TableTest({
    "Scenario                  | parallelized | applicationSpanActive",
    "serial suite              | false        | false                ",
    "parallel suite            | true         | false                ",
    "serial with application   | false        | true                 ",
    "parallel with application | true         | true                 "
  })
  void finishEventCompletesAnOutOfOrderTestWithoutDiscardingTheInnerTest(
      boolean parallelized, boolean applicationSpanActive) throws Exception {
    moduleSpan.swap();
    Context parent = Context.current();
    Context.setCaptureEnabled(false);
    TestSuiteImpl suite = suite(parallelized);
    Context suiteContext = Context.current();
    TestImpl outer = suite.testStart("outer", null, null);
    DDSpan outerSpan = (DDSpan) AgentTracer.activeSpan();
    TestImpl inner = suite.testStart("inner", null, null);
    AgentSpan applicationSpan = null;
    if (applicationSpanActive) {
      applicationSpan = tracer.startSpan("test", "application");
      applicationSpan.swap();
    }
    Context innerContext = Context.current();
    CoverageProbes innerProbes = mock(CoverageProbes.class);
    CoveragePerTestBridge.setThreadLocalCoverageProbes(innerProbes);
    ContextStore<Object, DDTest> tests = new StrongMapContextStore<>();
    Object testKey = new Object();
    tests.put(testKey, outer);
    TestEventsHandlerImpl<Object, Object> handler = handler(new StrongMapContextStore<>(), tests);
    AtomicInteger outerCallbacks = new AtomicInteger();

    try (AutoCloseable ignored = onTestEnd(outer, outerCallbacks::incrementAndGet)) {
      handler.onTestFinish(testKey, null, null);
      assertEquals(0, outerCallbacks.get());

      assertNull(tests.get(testKey));
      assertTrue(outerSpan.isFinished());
      assertSame(innerContext, Context.current());
      CoveragePerTestBridge.recordCoverage(getClass());
      verify(innerProbes).record(getClass());
      inner.end(null);
      assertEquals(0, outerCallbacks.get());
      assertSame(suiteContext, Context.current());
      suite.end(null);
      assertSame(parent, Context.current());
      assertFalse(Context.isCaptureEnabled());
    } finally {
      inner.end(null);
      outer.end(null);
      suite.end(null);
      if (applicationSpan != null) {
        applicationSpan.finish();
      }
      handler.close();
    }
  }

  @TableTest({
    "Order              | first | second | third",
    "outer middle inner | 0     | 1      | 2    ",
    "outer inner middle | 0     | 2      | 1    ",
    "middle outer inner | 1     | 0      | 2    ",
    "middle inner outer | 1     | 2      | 0    ",
    "inner outer middle | 2     | 0      | 1    ",
    "inner middle outer | 2     | 1      | 0    "
  })
  void nestedTestsRestoreLiveContextsInAnyTeardownOrder(int first, int second, int third) {
    moduleSpan.swap();
    Context parent = Context.current();
    Context.setCaptureEnabled(false);
    TestSuiteImpl suite = suite(false);
    Context suiteContext = Context.current();
    boolean suiteCaptureEnabled = Context.isCaptureEnabled();
    TestImpl[] tests = new TestImpl[3];
    DDSpan[] spans = new DDSpan[3];
    boolean[] ended = new boolean[3];
    try {
      for (int i = 0; i < tests.length; i++) {
        tests[i] = suite.testStart("test-" + i, null, null);
        spans[i] = (DDSpan) AgentTracer.activeSpan();
        Context.setCaptureEnabled(i % 2 == 0);
      }

      for (int index : new int[] {first, second, third}) {
        tests[index].end(null);
        ended[index] = true;
        assertTrue(spans[index].isFinished());
        int current = tests.length - 1;
        while (current >= 0 && ended[current]) {
          current--;
        }
        Context expected = current >= 0 ? spans[current] : suiteContext;
        boolean captureEnabled = current >= 0 ? current % 2 == 0 : suiteCaptureEnabled;
        assertSame(expected, Context.current());
        assertEquals(captureEnabled, Context.isCaptureEnabled());
        tests[index].end(null);
        assertSame(expected, Context.current());
        assertEquals(captureEnabled, Context.isCaptureEnabled());
      }
      suite.end(null);
      assertSame(parent, Context.current());
      assertFalse(Context.isCaptureEnabled());
    } finally {
      for (int i = tests.length - 1; i >= 0; i--) {
        if (tests[i] != null) {
          tests[i].end(null);
        }
      }
      suite.end(null);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testsCanStartWhileAnEndedOuterFrameIsWaitingForItsChildren(boolean parallelized) {
    TestSuiteImpl suite = suite(parallelized);
    Context parent = Context.current();
    TestImpl outer = suite.testStart("outer", null, null);
    TestImpl middle = suite.testStart("middle", null, null);
    outer.end(null);
    TestImpl inner = suite.testStart("inner", null, null);
    Context innerContext = Context.current();
    try {
      middle.end(null);
      assertSame(innerContext, Context.current());
      inner.end(null);
      assertSame(parent, Context.current());
    } finally {
      inner.end(null);
      middle.end(null);
      outer.end(null);
      suite.end(null);
    }
  }

  @Test
  void testEndCallbacksCanStartAndFinishNestedTests() throws Exception {
    Context parent = Context.current();
    TestSuiteImpl suite = suite(false);
    Context suiteContext = Context.current();
    TestImpl outer = suite.testStart("outer", null, null);
    Context outerContext = Context.current();
    try (AutoCloseable ignored =
        onTestEnd(
            outer,
            () -> {
              TestImpl inner = suite.testStart("inner", null, null);
              inner.end(null);
              assertSame(outerContext, Context.current());
            })) {
      outer.end(null);
      assertSame(suiteContext, Context.current());
    } finally {
      outer.end(null);
      suite.end(null);
    }
    assertSame(parent, Context.current());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testEndCallbacksCanLeaveANestedTestRunning(boolean callbackFails) throws Exception {
    Context parent = Context.current();
    TestSuiteImpl suite = suite(false);
    Context suiteContext = Context.current();
    TestImpl outer = suite.testStart("outer", null, null);
    DDSpan outerSpan = (DDSpan) AgentTracer.activeSpan();
    AtomicReference<TestImpl> inner = new AtomicReference<>();
    AtomicReference<Context> innerContext = new AtomicReference<>();
    CoverageProbes probes = mock(CoverageProbes.class);
    RuntimeException failure = new RuntimeException("test-end callback failed");
    try (AutoCloseable ignored =
        onTestEnd(
            outer,
            () -> {
              inner.set(suite.testStart("inner", null, null));
              innerContext.set(Context.current());
              CoveragePerTestBridge.setThreadLocalCoverageProbes(probes);
              if (callbackFails) {
                throw failure;
              }
            })) {
      if (callbackFails) {
        assertSame(failure, assertThrows(RuntimeException.class, () -> outer.end(null)));
      } else {
        outer.end(null);
      }
      assertNotNull(inner.get());
      assertTrue(outerSpan.isFinished());
      assertSame(innerContext.get(), Context.current());
      CoveragePerTestBridge.recordCoverage(getClass());
      verify(probes).record(getClass());
      inner.get().end(null);
      assertSame(suiteContext, Context.current());
    } finally {
      if (inner.get() != null) {
        inner.get().end(null);
      }
      outer.end(null);
      suite.end(null);
    }
    assertSame(parent, Context.current());
  }

  @Test
  void repeatedTestEndDoesNotRepeatCallbacksOrChangeTheCallerContext() throws Exception {
    TestSuiteImpl suite = suite(false);
    TestImpl test = suite.testStart("test", null, null);
    DDSpan testSpan = (DDSpan) AgentTracer.activeSpan();
    AtomicInteger callbacks = new AtomicInteger();
    try (AutoCloseable ignored = onTestEnd(test, callbacks::incrementAndGet)) {
      test.end(null);
      moduleSpan.swap();
      Context callerContext = Context.current();
      test.end(null);
      assertTrue(testSpan.isFinished());
      assertSame(callerContext, Context.current());
      assertEquals(1, callbacks.get());
    } finally {
      test.end(null);
      suite.end(null);
    }
  }

  @Test
  void reentrantTestEndWaitsForTheCallbackBeforeFinishing() throws Exception {
    TestSuiteImpl suite = suite(false);
    Context suiteContext = Context.current();
    TestImpl test = suite.testStart("test", null, null);
    DDSpan testSpan = (DDSpan) AgentTracer.activeSpan();
    try (AutoCloseable ignored =
        onTestEnd(
            test,
            () -> {
              test.end(null);
              assertFalse(testSpan.isFinished());
              assertSame(testSpan, AgentTracer.activeSpan());
            })) {
      test.end(null);
      assertTrue(testSpan.isFinished());
      assertSame(suiteContext, Context.current());
    } finally {
      test.end(null);
      suite.end(null);
    }
  }

  @Test
  void finishEventCompletesASuiteWithoutDiscardingTheActiveTest() {
    moduleSpan.swap();
    Context parent = Context.current();
    Context.setCaptureEnabled(false);
    AtomicInteger callbacks = new AtomicInteger();
    TestSuiteImpl suite = suite(false, span -> callbacks.incrementAndGet());
    DDSpan suiteSpan = (DDSpan) AgentTracer.activeSpan();
    TestImpl test = suite.testStart("test", null, null);
    Context testContext = Context.current();
    ContextStore<Object, DDTestSuite> suites = new StrongMapContextStore<>();
    Object suiteKey = new Object();
    suites.put(suiteKey, suite);
    TestEventsHandlerImpl<Object, Object> handler = handler(suites, new StrongMapContextStore<>());

    handler.onTestSuiteFinish(suiteKey, null);

    assertNull(suites.get(suiteKey));
    assertTrue(suiteSpan.isFinished());
    assertEquals(1, callbacks.get());
    assertSame(testContext, Context.current());
    test.end(null);
    assertSame(parent, Context.current());
    assertFalse(Context.isCaptureEnabled());
    suite.end(null);
    assertEquals(1, callbacks.get());
    handler.close();
  }

  @Test
  void repeatedSuiteEndDoesNotRepeatTheCallbackOrChangeTheCallerContext() {
    AtomicInteger callbacks = new AtomicInteger();
    TestSuiteImpl suite = suite(false, span -> callbacks.incrementAndGet());
    suite.end(null);
    moduleSpan.swap();
    Context callerContext = Context.current();
    assertDoesNotThrow(() -> suite.end(null));
    assertSame(callerContext, Context.current());
    assertEquals(1, callbacks.get());
  }

  private static AutoCloseable onTestEnd(TestImpl test, Runnable callback) {
    AtomicReference<TestContext> target = new AtomicReference<>(test.getContext());
    AtomicReference<Runnable> action = new AtomicReference<>(callback);
    InstrumentationTestBridge.registerListener(context -> {
      if (target.compareAndSet(context, null)) {
        Runnable current = action.getAndSet(null);
        if (current != null) {
          current.run();
        }
      }
    });
    return () -> {
      target.set(null);
      action.set(null);
    };
  }

  private static TestEventsHandlerImpl<Object, Object> handler(
      ContextStore<Object, DDTestSuite> suites, ContextStore<Object, DDTest> tests) {
    TestFrameworkSession session = mock(TestFrameworkSession.class);
    when(session.testModuleStart("module", null)).thenReturn(mock(TestFrameworkModule.class));
    return new TestEventsHandlerImpl<>(
        mock(CiVisibilityMetricCollector.class), () -> session, "module", false, suites, tests);
  }

  private TestSuiteImpl suite(boolean parallelized) {
    return suite(parallelized, SpanTagsPropagator.NOOP_PROPAGATOR);
  }

  private TestSuiteImpl suite(boolean parallelized, Consumer<AgentSpan> onSpanFinish) {
    return suite(parallelized, onSpanFinish, new NoOpCoverageStore.Factory());
  }

  private TestSuiteImpl suite(
      boolean parallelized,
      Consumer<AgentSpan> onSpanFinish,
      CoverageStore.Factory coverageStoreFactory) {
    return new TestSuiteImpl(
        moduleSpan.spanContext(),
        "module",
        "suite",
        null,
        false,
        null,
        null,
        parallelized,
        InstrumentationType.BUILD,
        TestFrameworkInstrumentation.OTHER,
        Config.get(),
        mock(CiVisibilityMetricCollector.class),
        new TestDecoratorImpl("component", "session", "command", emptyMap()),
        NoOpSourcePathResolver.INSTANCE,
        NoCodeowners.INSTANCE,
        mock(LinesResolver.class),
        coverageStoreFactory,
        new ExecutionResults(),
        ConfigurationErrors.NONE,
        emptyList(),
        onSpanFinish);
  }
}
