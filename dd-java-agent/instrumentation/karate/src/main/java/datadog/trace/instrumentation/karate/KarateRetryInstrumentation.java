package datadog.trace.instrumentation.karate;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import com.intuit.karate.RuntimeHook;
import com.intuit.karate.core.Result;
import com.intuit.karate.core.Scenario;
import com.intuit.karate.core.ScenarioRuntime;
import com.intuit.karate.core.StepResult;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class KarateRetryInstrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForKnownTypes {

  public KarateRetryInstrumentation() {
    super("ci-visibility", "karate", "test-retry");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems) && Config.get().isCiVisibilityFlakyRetryEnabled();
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "com.intuit.karate.core.ScenarioRuntime", "com.intuit.karate.core.ScenarioResult"
    };
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TestEventsHandlerHolder",
      packageName + ".KarateUtils",
      packageName + ".KarateTracingHook",
      packageName + ".RetryContext"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "com.intuit.karate.core.Scenario", packageName + ".RetryContext");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // ScenarioRuntime
    transformer.applyAdvice(
        named("run").and(takesNoArguments()),
        KarateRetryInstrumentation.class.getName() + "$RetryAdvice");

    // ScenarioResult
    transformer.applyAdvice(
        named("addStepResult")
            .and(takesArguments(1))
            .and(takesArgument(0, named("com.intuit.karate.core.StepResult"))),
        KarateRetryInstrumentation.class.getName() + "$SuppressErrorAdvice");
  }

  public static class RetryAdvice {
    @Advice.OnMethodEnter
    public static void beforeExecute(@Advice.This ScenarioRuntime scenarioRuntime) {
      InstrumentationContext.get(Scenario.class, RetryContext.class)
          .computeIfAbsent(scenarioRuntime.scenario, RetryContext::create);
    }

    @Advice.OnMethodExit
    public static void afterExecute(@Advice.This ScenarioRuntime scenarioRuntime) {
      Scenario scenario = scenarioRuntime.scenario;
      RetryContext retryContext =
          InstrumentationContext.get(Scenario.class, RetryContext.class).get(scenario);
      if (retryContext == null) {
        return;
      }

      TestRetryPolicy retryPolicy = retryContext.getRetryPolicy();
      if (retryPolicy.retry(!retryContext.getAndResetFailed())) {
        ScenarioRuntime retry =
            new ScenarioRuntime(scenarioRuntime.featureRuntime, scenarioRuntime.scenario);
        retry.run();
        retry.featureRuntime.result.addResult(retry.result);
      }
    }

    // Karate 1.0.0 and above
    public static void muzzleCheck(RuntimeHook runtimeHook) {
      runtimeHook.beforeSuite(null);
    }
  }

  public static class SuppressErrorAdvice {
    @Advice.OnMethodEnter
    public static void onAddingStepResult(
        @Advice.Argument(value = 0, readOnly = false) StepResult stepResult,
        @Advice.FieldValue("scenario") Scenario scenario) {

      Result result = stepResult.getResult();
      if (result.isFailed()) {
        RetryContext retryContext =
            InstrumentationContext.get(Scenario.class, RetryContext.class).get(scenario);
        if (retryContext == null) {
          return;
        }

        retryContext.setFailed(true);

        TestRetryPolicy retryPolicy = retryContext.getRetryPolicy();
        if (retryPolicy.suppressFailures() && retryPolicy.retryPossible()) {
          stepResult = new StepResult(stepResult.getStep(), KarateUtils.abortedResult());
          stepResult.setFailedReason(result.getError());
          stepResult.setErrorIgnored(true);
        }
      }
    }

    // Karate 1.0.0 and above
    public static void muzzleCheck(RuntimeHook runtimeHook) {
      runtimeHook.beforeSuite(null);
    }
  }
}
