package datadog.trace.instrumentation.karate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.condition.JRE.JAVA_23;

import com.intuit.karate.Results;
import com.intuit.karate.Runner;
import com.intuit.karate.RuntimeHook;
import com.intuit.karate.core.Feature;
import com.intuit.karate.core.ScenarioRuntime;
import com.intuit.karate.core.Step;
import datadog.context.Context;
import datadog.context.ContextHandle;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.civisibility.execution.TestExecutionTracker;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.civisibility.events.NoOpTestEventsHandler;
import datadog.trace.civisibility.utils.StrongMapContextStore;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;

@WithConfig(key = "legacy.context-manager.enabled", value = "false")
@EnabledForJreRange(max = JAVA_23)
class StepVetoForkedTest extends StepContextRestorationTest {
  @BeforeAll
  static void setUpHandlerFactory() {
    // This fork owns the handler factory; scenario events do not create additional test spans.
    InstrumentationBridge.registerTestEventsHandlerFactory(new NoOpTestEventsHandler.Factory());
  }

  @AfterEach
  void stopHandler() {
    TestEventsHandlerHolder.stop();
  }

  @Test
  void vetoedMiddleAndFinalStepsAreFinishedBeforeTheTestEnds() {
    Context.setCaptureEnabled(false);
    Feature feature = Feature.read("classpath:org/example/test_vetoed_steps.feature");
    ContextStore<ScenarioRuntime, KarateTracingHook.StepContext> steps =
        new StrongMapContextStore<>();
    KarateTracingHook hook = new KarateTracingHook(new StrongMapContextStore<>(), steps);
    List<DDSpan> stepSpans = new ArrayList<>();
    AtomicInteger testEnds = new AtomicInteger();
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER =
        new NoOpTestEventsHandler<TestSuiteDescriptor, TestDescriptor>() {
          @Override
          public void onTestFinish(
              TestDescriptor descriptor, Long endTime, TestExecutionTracker executionTracker) {
            assertSame(parent, Context.current());
            assertFalse(Context.isCaptureEnabled());
            assertEquals(4, stepSpans.size());
            assertTrue(stepSpans.stream().allMatch(DDSpan::isFinished));
            testEnds.incrementAndGet();
          }
        };
    AtomicReference<ScenarioRuntime> scenario = new AtomicReference<>();
    RuntimeHook veto =
        new RuntimeHook() {
          private ContextHandle previous;

          @Override
          public boolean beforeScenario(ScenarioRuntime runtime) {
            scenario.set(runtime);
            previous = parent.swap();
            Context.setCaptureEnabled(false);
            return true;
          }

          @Override
          public void afterScenario(ScenarioRuntime runtime) {
            previous.swap();
          }

          @Override
          public boolean beforeStep(Step step, ScenarioRuntime runtime) {
            DDSpan span = (DDSpan) AgentTracer.activeSpan();
            assertEquals(KarateTracingHook.KARATE_STEP_SPAN_NAME, span.getSpanName().toString());
            assertEquals(parent.getSpanId(), span.getParentId());
            if (stepSpans.size() == 2) {
              assertTrue(stepSpans.get(1).isFinished());
            }
            stepSpans.add(span);
            return !step.getText().startsWith("def veto");
          }
        };
    Results result = Runner.builder().features(feature).hook(hook).hook(veto).parallel(1);

    assertEquals(0, result.getFailCount());
    assertEquals(1, testEnds.get());
    assertNull(steps.get(scenario.get()));
    assertSame(parent, Context.current());
    assertFalse(Context.isCaptureEnabled());
    assertEquals(4, stepSpans.size());
    assertTrue(stepSpans.stream().allMatch(DDSpan::isFinished));
  }
}
