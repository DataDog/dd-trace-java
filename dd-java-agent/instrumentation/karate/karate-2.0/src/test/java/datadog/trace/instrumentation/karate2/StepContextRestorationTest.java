package datadog.trace.instrumentation.karate2;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.ContextHandle;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.civisibility.utils.StrongMapContextStore;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import io.karatelabs.common.Resource;
import io.karatelabs.core.KarateJs;
import io.karatelabs.core.ScenarioRuntime;
import io.karatelabs.core.StepResult;
import io.karatelabs.core.StepRunEvent;
import io.karatelabs.gherkin.Feature;
import io.karatelabs.gherkin.Scenario;
import io.karatelabs.gherkin.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@WithConfig(key = "legacy.context-manager.enabled", value = "false")
class StepContextRestorationTest extends DDJavaSpecification {
  private CoreTracer tracer;
  private AgentTracer.TracerAPI previousTracer;
  private ContextHandle previousContext;
  DDSpan parent;

  @BeforeEach
  void setUpTracer() {
    previousTracer = AgentTracer.get();
    tracer = CoreTracer.builder().writer(new ListWriter()).build();
    AgentTracer.forceRegister(tracer);
    AgentTracer.installLegacyContextManager();
    previousContext = Context.current().checkpoint();
    parent = (DDSpan) tracer.startSpan("test", "parent");
    parent.setSpanType(InternalSpanTypes.TEST);
    parent.swap();
  }

  @AfterEach
  void restoreTracer() {
    previousContext.swap();
    parent.finish();
    tracer.close();
    AgentTracer.forceRegister(previousTracer);
  }

  @Test
  void savedStepIsFinishedEvenWhenAnApplicationSpanIsLeftActive() {
    Resource resource = Resource.path("classpath:org/example/test_succeed_one_case.feature");
    Feature feature = Feature.read(resource);
    Scenario scenario = feature.getSections().get(0).getScenario();
    ScenarioRuntime runtime = new ScenarioRuntime(new KarateJs(resource), scenario);
    Step step = scenario.getSteps().get(0);
    ContextStore<ScenarioRuntime, KarateTracingListener.StepContext> steps =
        new StrongMapContextStore<>();
    KarateTracingListener hook = new KarateTracingListener(new StrongMapContextStore<>(), steps);
    hook.onEvent(StepRunEvent.enter(step, runtime));
    DDSpan stepSpan = (DDSpan) AgentTracer.activeSpan();
    DDSpan applicationSpan = (DDSpan) tracer.startSpan("test", "application");
    applicationSpan.swap();

    hook.onEvent(StepRunEvent.exit(StepResult.passed(step, 0, 0), runtime));

    assertTrue(stepSpan.isFinished());
    assertSame(parent, Context.current());
    assertNull(steps.get(runtime));
    hook.onEvent(StepRunEvent.exit(StepResult.passed(step, 0, 0), runtime));
    assertSame(parent, Context.current());
    applicationSpan.finish();
  }
}
