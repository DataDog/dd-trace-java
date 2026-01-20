package datadog.trace.instrumentation.karate;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import com.intuit.karate.RuntimeHook;
import com.intuit.karate.core.Result;
import com.intuit.karate.core.Scenario;
import com.intuit.karate.core.ScenarioResult;
import com.intuit.karate.core.ScenarioRuntime;
import com.intuit.karate.core.StepResult;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class KarateExecutionInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public KarateExecutionInstrumentation() {
    super("ci-visibility", "karate", "test-retry");
  }

  @Override
  public boolean isEnabled() {
    return Config.get().isCiVisibilityExecutionPoliciesEnabled();
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
      packageName + ".KarateUtils",
      packageName + ".TestEventsHandlerHolder",
      packageName + ".KarateTracingHook",
      packageName + ".ExecutionContext"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "com.intuit.karate.core.Scenario", packageName + ".ExecutionContext");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // ScenarioRuntime
    transformer.applyAdvice(
        named("run").and(takesNoArguments()),
        KarateExecutionInstrumentation.class.getName() + "$RetryAdvice");

    // ScenarioResult
    transformer.applyAdvice(
        named("addStepResult")
            .and(takesArguments(1))
            .and(takesArgument(0, named("com.intuit.karate.core.StepResult"))),
        KarateExecutionInstrumentation.class.getName() + "$SuppressErrorAdvice");
  }

  public static class RetryAdvice {
    @Advice.OnMethodEnter
    public static void beforeExecute(@Advice.This ScenarioRuntime scenarioRuntime) {
      ExecutionContext executionContext =
          InstrumentationContext.get(Scenario.class, ExecutionContext.class)
              .computeIfAbsent(scenarioRuntime.scenario, ExecutionContext::create);

      // Indicate beforehand if the failures should be suppressed. This aligns the ordering with the
      // rest of the frameworks
      TestExecutionPolicy executionPolicy = executionContext.getExecutionPolicy();
      executionContext.setSuppressFailures(executionPolicy.suppressFailures());

      scenarioRuntime.magicVariables.putIfAbsent(
          KarateUtils.EXECUTION_HISTORY_MAGICVARIABLE, executionPolicy);
    }

    @Advice.OnMethodExit
    public static void afterExecute(@Advice.This ScenarioRuntime scenarioRuntime) {
      if (CallDepthThreadLocalMap.incrementCallDepth(ScenarioRuntime.class) > 0) {
        // nested call
        return;
      }

      Scenario scenario = scenarioRuntime.scenario;
      ExecutionContext context =
          InstrumentationContext.get(Scenario.class, ExecutionContext.class).get(scenario);
      if (context == null) {
        return;
      }

      ScenarioResult finalResult = scenarioRuntime.result;

      TestExecutionPolicy executionPolicy = context.getExecutionPolicy();
      while (executionPolicy.applicable()) {
        ScenarioRuntime retry =
            new ScenarioRuntime(scenarioRuntime.featureRuntime, scenarioRuntime.scenario);
        retry.magicVariables.put(KarateUtils.EXECUTION_HISTORY_MAGICVARIABLE, executionPolicy);
        retry.run();
        retry.featureRuntime.result.addResult(retry.result);
        finalResult = retry.result;
      }

      KarateUtils.setResult(scenarioRuntime, finalResult);

      CallDepthThreadLocalMap.reset(ScenarioRuntime.class);
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
        ExecutionContext executionContext =
            InstrumentationContext.get(Scenario.class, ExecutionContext.class).get(scenario);
        if (executionContext == null) {
          return;
        }

        if (executionContext.getAndResetSuppressFailures()) {
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
