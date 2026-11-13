package datadog.trace.instrumentation.karate2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
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
import io.karatelabs.common.Resource;
import io.karatelabs.core.FeatureRuntime;
import io.karatelabs.core.RunEventType;
import io.karatelabs.core.RunListener;
import io.karatelabs.core.Runner;
import io.karatelabs.core.ScenarioRuntime;
import io.karatelabs.core.StepRunEvent;
import io.karatelabs.core.Suite;
import io.karatelabs.gherkin.Feature;
import io.karatelabs.gherkin.Scenario;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@WithConfig(key = "legacy.context-manager.enabled", value = "false")
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
    Feature feature =
        Feature.read(Resource.path("classpath:org/example/test_vetoed_steps.feature"));
    Scenario scenario = feature.getSections().get(0).getScenario();
    ContextStore<ScenarioRuntime, KarateTracingListener.StepContext> steps =
        new StrongMapContextStore<>();
    KarateTracingListener listener =
        new KarateTracingListener(new StrongMapContextStore<>(), steps);
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
    RunListener veto =
        event -> {
          if (event.getType() != RunEventType.STEP_ENTER) {
            return true;
          }
          DDSpan span = (DDSpan) AgentTracer.activeSpan();
          assertEquals(KarateTracingListener.KARATE_STEP_SPAN_NAME, span.getSpanName().toString());
          assertEquals(parent.getSpanId(), span.getParentId());
          if (stepSpans.size() == 2) {
            assertTrue(stepSpans.get(1).isFinished());
          }
          stepSpans.add(span);
          return !((StepRunEvent) event).step().getText().startsWith("def veto");
        };
    Suite suite = Runner.features(feature).listener(listener).listener(veto).buildSuite();
    ScenarioRuntime runtime = new ScenarioRuntime(FeatureRuntime.of(suite, feature), scenario);

    assertFalse(runtime.call().isFailed());

    assertEquals(1, testEnds.get());
    assertNull(steps.get(runtime));
    assertSame(parent, Context.current());
    assertFalse(Context.isCaptureEnabled());
    assertEquals(4, stepSpans.size());
    assertTrue(stepSpans.stream().allMatch(DDSpan::isFinished));
  }
}
