package datadog.trace.instrumentation.karate2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.DisableTestTrace;
import datadog.trace.api.civisibility.DDTest;
import datadog.trace.api.civisibility.DDTestSuite;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.config.LibraryCapability;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.execution.TestExecutionTracker;
import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.civisibility.events.NoOpTestEventsHandler;
import datadog.trace.civisibility.execution.AutoTestRetry;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import io.karatelabs.core.Runner;
import io.karatelabs.core.SuiteResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;

@DisableTestTrace(
    reason = "exercise the first Karate scenario without tracing the outer JUnit test")
@WithConfig(key = "legacy.context-manager.enabled", value = "true")
@WithConfig(key = "civisibility.enabled", value = "true")
class LegacyColdStartRetryForkedTest extends AbstractInstrumentationTest {
  @Test
  void firstScenarioIsRetriedWithoutPreloadingItsRuntime() {
    assertFalse(
        Arrays.stream(ByteBuddyAgent.getInstrumentation().getAllLoadedClasses())
            .anyMatch(type -> "io.karatelabs.core.ScenarioRuntime".equals(type.getName())),
        "The test must start before ScenarioRuntime has been loaded");
    RetryHandler handler = new RetryHandler();
    InstrumentationBridge.registerTestEventsHandlerFactory(
        new TestEventsHandler.Factory() {
          @Override
          @SuppressWarnings("unchecked")
          public <SuiteKey, TestKey> TestEventsHandler<SuiteKey, TestKey> create(
              String component,
              ContextStore<SuiteKey, DDTestSuite> suites,
              ContextStore<TestKey, DDTest> tests,
              Collection<LibraryCapability> capabilities) {
            return (TestEventsHandler<SuiteKey, TestKey>) handler;
          }
        });

    SuiteResult result =
        Runner.path("classpath:org/example/test_failed_then_succeed.feature").parallel(1);

    assertEquals(
        1, handler.policyRequests, "Retry advice must request a policy for the first scenario");
    assertEquals(0, result.getScenarioFailedCount(), () -> result.getErrors().toString());
    assertEquals(3, handler.attempts.size());
    assertEquals(2, handler.retries.get());
    assertTrue(handler.attempts.get(0).isError());
    assertTrue(handler.attempts.get(1).isError());
    assertFalse(handler.attempts.get(2).isError());
    assertTrue(handler.attempts.stream().allMatch(DDSpan::isFinished));
    assertNull(AgentTracer.activeSpan());
  }

  private static final class RetryHandler
      extends NoOpTestEventsHandler<TestSuiteDescriptor, TestDescriptor> {
    private final AtomicInteger retries = new AtomicInteger();
    private final TestExecutionPolicy policy = new AutoTestRetry(3, false, retries);
    private final List<DDSpan> attempts = new ArrayList<>();
    private int policyRequests;
    private DDSpan current;

    @Override
    public TestExecutionPolicy executionPolicy(
        TestIdentifier test, TestSourceData source, Collection<String> tags) {
      policyRequests++;
      return policy;
    }

    @Override
    public void onTestStart(
        TestSuiteDescriptor suite,
        TestDescriptor test,
        String name,
        String framework,
        String version,
        String parameters,
        Collection<String> tags,
        TestSourceData source,
        Long startTime,
        TestExecutionTracker tracker) {
      assertSame(policy, tracker, "Retry advice must apply to the first scenario invocation");
      current = (DDSpan) AgentTracer.get().startSpan("test", "karate.test");
      current.setSpanType(InternalSpanTypes.TEST);
      attempts.add(current);
      AgentTracer.get().activateSpanWithoutScope(current);
    }

    @Override
    public void onTestFailure(TestDescriptor test, Throwable error) {
      current.addThrowable(error);
    }

    @Override
    public void onTestFinish(TestDescriptor test, Long endTime, TestExecutionTracker tracker) {
      assertSame(current, AgentTracer.activeSpan());
      tracker.registerExecution(current.isError() ? TestStatus.fail : TestStatus.pass, 0);
      AgentTracer.get().closeActive();
      current.finish();
    }
  }
}
