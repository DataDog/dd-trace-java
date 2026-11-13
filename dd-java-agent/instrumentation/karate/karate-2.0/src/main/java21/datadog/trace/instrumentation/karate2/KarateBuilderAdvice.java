package datadog.trace.instrumentation.karate2;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import io.karatelabs.core.RunEvent;
import io.karatelabs.core.RunListener;
import io.karatelabs.core.Runner;
import io.karatelabs.gherkin.Scenario;
import net.bytebuddy.asm.Advice;

/** Advice for the {@code io.karatelabs.core.Runner.Builder} constructor. */
public class KarateBuilderAdvice {

  @Advice.OnMethodExit
  public static void onRunnerBuilderConstructorExit(@Advice.This Runner.Builder builder) {
    ContextStore<Scenario, ExecutionContext> scenarioContext =
        InstrumentationContext.get(Scenario.class, ExecutionContext.class);
    // Keep ScenarioRuntime unloaded until its retry advice can be applied on first use.
    builder.listener(
        new KarateTracingListener(
            scenarioContext,
            InstrumentationContext.get(
                "io.karatelabs.core.ScenarioRuntime",
                "datadog.trace.instrumentation.karate2.KarateTracingListener$StepContext")));
  }

  // Karate 2.0.0 and above
  public static void muzzleCheck(RunListener runListener) {
    runListener.onEvent((RunEvent) null);
  }
}
