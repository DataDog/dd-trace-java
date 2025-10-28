package datadog.trace.instrumentation.junit4.execution;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.execution.TestExecutionHistory;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.instrumentation.junit4.CucumberUtils;
import datadog.trace.instrumentation.junit4.JUnit4Utils;
import datadog.trace.instrumentation.junit4.TestEventsHandlerHolder;
import datadog.trace.util.Strings;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.invoke.MethodHandle;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import org.junit.runner.Description;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.ParentRunner;

@AutoService(InstrumenterModule.class)
public class Cucumber4ExecutionInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private final String parentPackageName = Strings.getPackageName(JUnit4Utils.class.getName());

  public Cucumber4ExecutionInstrumentation() {
    super("ci-visibility", "junit-4", "junit-4-cucumber", "test-retry");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems)
        && Config.get().isCiVisibilityExecutionPoliciesEnabled();
  }

  @Override
  public String instrumentedType() {
    return "io.cucumber.junit.FeatureRunner";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      parentPackageName + ".SkippedByDatadog",
      parentPackageName + ".CucumberUtils",
      parentPackageName + ".JUnit4Utils",
      parentPackageName + ".TracingListener",
      parentPackageName + ".TestEventsHandlerHolder",
      packageName + ".FailureSuppressingNotifier",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "org.junit.runner.Description", TestExecutionHistory.class.getName());
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return CucumberUtils.MuzzleHelper.additionalMuzzleReferences();
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("runChild")
            .and(takesArgument(0, named("io.cucumber.junit.PickleRunners$PickleRunner")))
            .and(takesArgument(1, named("org.junit.runner.notification.RunNotifier"))),
        Cucumber4ExecutionInstrumentation.class.getName() + "$ExecutionAdvice");
  }

  public static class ExecutionAdvice {
    @SuppressWarnings("bytebuddy-exception-suppression")
    @SuppressFBWarnings("NP_BOOLEAN_RETURN_NULL")
    @Advice.OnMethodEnter(skipOn = Boolean.class)
    public static Boolean execute(
        @Advice.SelfCallHandle(bound = false) MethodHandle runPickle,
        @Advice.This ParentRunner<?> /* io.cucumber.junit.FeatureRunner */ featureRunner,
        @Advice.Argument(0) Object /* io.cucumber.junit.PickleRunners.PickleRunner */ pickleRunner,
        @Advice.Argument(1) RunNotifier notifier) {
      if (notifier instanceof FailureSuppressingNotifier) {
        // notifier already wrapped, run original method
        return null;
      }

      Description description = CucumberUtils.getPickleRunnerDescription(pickleRunner);
      TestIdentifier testIdentifier = CucumberUtils.toTestIdentifier(description);
      Collection<String> testTags = CucumberUtils.getPickleRunnerTags(pickleRunner);
      TestExecutionPolicy executionPolicy =
          TestEventsHandlerHolder.HANDLERS
              .get(TestFrameworkInstrumentation.CUCUMBER)
              .executionPolicy(testIdentifier, TestSourceData.UNKNOWN, testTags);
      if (!executionPolicy.applicable()) {
        // retries not applicable, run original method
        return null;
      }

      InstrumentationContext.get(Description.class, TestExecutionHistory.class)
          .put(description, executionPolicy);

      FailureSuppressingNotifier failureSuppressingNotifier =
          new FailureSuppressingNotifier(executionPolicy, notifier);
      do {
        try {
          runPickle.invokeWithArguments(featureRunner, pickleRunner, failureSuppressingNotifier);
        } catch (Throwable ignored) {
        }
      } while (executionPolicy.applicable());

      // skip original method
      return Boolean.TRUE;
    }
  }
}
