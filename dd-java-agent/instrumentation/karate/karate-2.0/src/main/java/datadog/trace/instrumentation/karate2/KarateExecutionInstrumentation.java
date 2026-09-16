package datadog.trace.instrumentation.karate2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Collections;
import java.util.Map;

/**
 * Drives test-retry execution policies (ATR / EFD / attempt-to-fix) and failure suppression for
 * Karate v2.
 *
 * <ul>
 *   <li>{@code ScenarioRuntime#call()} is advised to re-run the scenario while the execution policy
 *       is applicable, overriding the returned {@code ScenarioResult} with the final attempt.
 *   <li>{@code ScenarioResult#addStepResult(StepResult)} is advised to replace a failing step with
 *       a skipped one when the policy requests failure suppression.
 * </ul>
 *
 * Compiled for Java 8 (see {@link KarateInstrumentation}); the advice lives in the {@code java21}
 * source set.
 */
@AutoService(InstrumenterModule.class)
public class KarateExecutionInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public KarateExecutionInstrumentation() {
    super("ci-visibility", "karate", "test-retry");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"io.karatelabs.core.ScenarioRuntime", "io.karatelabs.core.ScenarioResult"};
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".KarateUtils",
      packageName + ".TestEventsHandlerHolder",
      packageName + ".ExecutionContext",
      packageName + ".KarateTracingListener",
      packageName + ".KarateScenarioAdvice",
      packageName + ".KarateScenarioAdvice$RetryAdvice",
      packageName + ".KarateScenarioAdvice$SuppressErrorAdvice"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "io.karatelabs.gherkin.Scenario", packageName + ".ExecutionContext");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // ScenarioRuntime#call() is the run()-equivalent; match the concrete ScenarioResult-returning
    // method, not the synthetic Callable#call() bridge.
    transformer.applyAdvice(
        named("call")
            .and(takesNoArguments())
            .and(returns(named("io.karatelabs.core.ScenarioResult")))
            .and(not(isBridge())),
        packageName + ".KarateScenarioAdvice$RetryAdvice");

    // ScenarioResult#addStepResult(StepResult)
    transformer.applyAdvice(
        named("addStepResult")
            .and(takesArguments(1))
            .and(takesArgument(0, named("io.karatelabs.core.StepResult"))),
        packageName + ".KarateScenarioAdvice$SuppressErrorAdvice");
  }
}
