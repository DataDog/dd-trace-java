package datadog.trace.instrumentation.karate2;

import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import io.karatelabs.core.RunEvent;
import io.karatelabs.core.RunListener;
import io.karatelabs.core.ScenarioResult;
import io.karatelabs.core.ScenarioRuntime;
import io.karatelabs.core.StepResult;
import io.karatelabs.gherkin.Scenario;
import net.bytebuddy.asm.Advice;

/** Advice classes for {@code io.karatelabs.core.ScenarioRuntime}/{@code ScenarioResult}. */
public class KarateScenarioAdvice {

  public static class RetryAdvice {
    @Advice.OnMethodEnter
    public static void beforeExecute(@Advice.This ScenarioRuntime scenarioRuntime) {
      if (KarateTracingListener.skipTracking(scenarioRuntime)) {
        return;
      }

      ExecutionContext executionContext =
          InstrumentationContext.get(Scenario.class, ExecutionContext.class)
              .computeIfAbsent(scenarioRuntime.getScenario(), ExecutionContext::create);

      // Indicate beforehand whether failures should be suppressed. This aligns the ordering with
      // the rest of the frameworks.
      TestExecutionPolicy executionPolicy = executionContext.getExecutionPolicy();
      executionContext.setSuppressFailures(executionPolicy.suppressFailures());
    }

    @Advice.OnMethodExit
    public static void afterExecute(
        @Advice.This ScenarioRuntime scenarioRuntime,
        @Advice.Return(readOnly = false) ScenarioResult result) {
      if (KarateTracingListener.skipTracking(scenarioRuntime)) {
        return;
      }

      if (CallDepthThreadLocalMap.incrementCallDepth(ScenarioRuntime.class) > 0) {
        // nested call (a retry invoked below, or a called scenario)
        return;
      }

      try {
        Scenario scenario = scenarioRuntime.getScenario();
        ExecutionContext context =
            InstrumentationContext.get(Scenario.class, ExecutionContext.class).get(scenario);
        if (context == null) {
          return;
        }

        ScenarioResult finalResult = result;
        TestExecutionPolicy executionPolicy = context.getExecutionPolicy();
        while (executionPolicy.applicable()) {
          ScenarioRuntime retry =
              new ScenarioRuntime(scenarioRuntime.getFeatureRuntime(), scenario);
          finalResult = retry.call();
        }

        // override the return value so the final attempt is the one recorded.
        result = finalResult;
      } finally {
        CallDepthThreadLocalMap.reset(ScenarioRuntime.class);
      }
    }

    // Karate 2.0.0 and above
    public static void muzzleCheck(RunListener runListener) {
      runListener.onEvent((RunEvent) null);
    }
  }

  public static class SuppressErrorAdvice {
    @Advice.OnMethodEnter
    public static void onAddingStepResult(
        @Advice.Argument(value = 0, readOnly = false) StepResult stepResult,
        @Advice.FieldValue("scenario") Scenario scenario) {

      if (stepResult.isFailed()) {
        ExecutionContext executionContext =
            InstrumentationContext.get(Scenario.class, ExecutionContext.class).get(scenario);
        if (executionContext == null) {
          return;
        }

        // Suppress every failing step of a to-be-retried attempt (not just the first): with
        // continueOnStepFailure a single attempt can add multiple failing steps, and any leak
        // would mark the retry attempt's result failed.
        if (executionContext.shouldSuppressFailures()) {
          // v2 StepResult is immutable: preserve the error out-of-band, then replace the failing
          // step with a skipped one so the scenario no longer counts as failed.
          executionContext.setSuppressedError(stepResult.getError());
          stepResult = StepResult.skipped(stepResult.getStep(), stepResult.getStartTime());
        }
      }
    }

    // Karate 2.0.0 and above
    public static void muzzleCheck(RunListener runListener) {
      runListener.onEvent((RunEvent) null);
    }
  }

  private KarateScenarioAdvice() {}
}
