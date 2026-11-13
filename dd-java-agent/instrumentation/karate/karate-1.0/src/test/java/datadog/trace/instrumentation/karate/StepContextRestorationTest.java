package datadog.trace.instrumentation.karate;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.condition.JRE.JAVA_23;

import com.intuit.karate.core.Feature;
import com.intuit.karate.core.FeatureRuntime;
import com.intuit.karate.core.Scenario;
import com.intuit.karate.core.ScenarioRuntime;
import com.intuit.karate.core.Step;
import com.intuit.karate.core.StepResult;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;

@WithConfig(key = "legacy.context-manager.enabled", value = "false")
@EnabledForJreRange(max = JAVA_23)
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
    Feature feature = Feature.read("classpath:org/example/test_succeed_one_case.feature");
    Scenario scenario = feature.getSections().get(0).getScenario();
    ScenarioRuntime runtime = new ScenarioRuntime(FeatureRuntime.of(feature), scenario);
    Step step = scenario.getSteps().get(0);
    ContextStore<ScenarioRuntime, KarateTracingHook.StepContext> steps =
        new StrongMapContextStore<>();
    KarateTracingHook hook = new KarateTracingHook(new StrongMapContextStore<>(), steps);
    hook.beforeStep(step, runtime);
    DDSpan stepSpan = (DDSpan) AgentTracer.activeSpan();
    DDSpan applicationSpan = (DDSpan) tracer.startSpan("test", "application");
    applicationSpan.swap();

    hook.afterStep(new StepResult(step, null), runtime);

    assertTrue(stepSpan.isFinished());
    assertSame(parent, Context.current());
    assertNull(steps.get(runtime));
    hook.afterStep(new StepResult(step, null), runtime);
    assertSame(parent, Context.current());
    applicationSpan.finish();
  }
}
