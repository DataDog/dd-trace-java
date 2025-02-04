package datadog.trace.instrumentation.junit4.execution;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.execution.TestExecutionHistory;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.instrumentation.junit4.JUnit4Utils;
import datadog.trace.instrumentation.junit4.MUnitUtils;
import datadog.trace.instrumentation.junit4.TestEventsHandlerHolder;
import datadog.trace.util.Strings;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import munit.MUnitRunner;
import net.bytebuddy.asm.Advice;
import org.junit.runner.Description;
import org.junit.runner.notification.RunNotifier;
import scala.concurrent.Future;

@AutoService(InstrumenterModule.class)
public class MUnitExecutionInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private final String parentPackageName = Strings.getPackageName(JUnit4Utils.class.getName());

  public MUnitExecutionInstrumentation() {
    super("ci-visibility", "junit-4", "junit-4-munit", "test-retry");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems)
        && Config.get().isCiVisibilityExecutionPoliciesEnabled();
  }

  @Override
  public String instrumentedType() {
    return "munit.MUnitRunner";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      parentPackageName + ".MUnitUtils",
      parentPackageName + ".SkippedByDatadog",
      parentPackageName + ".JUnit4Utils",
      parentPackageName + ".TracingListener",
      parentPackageName + ".TestEventsHandlerHolder",
      packageName + ".FailureSuppressingNotifier"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "org.junit.runner.Description", TestExecutionHistory.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("runTest").and(takesArgument(0, named("org.junit.runner.notification.RunNotifier"))),
        MUnitExecutionInstrumentation.class.getName() + "$ExecutionAdvice");
  }

  public static class ExecutionAdvice {
    @SuppressWarnings("bytebuddy-exception-suppression")
    @Advice.OnMethodEnter(skipOn = Future.class)
    public static Future<?> apply(
        @Advice.Origin Method runTest,
        @Advice.This MUnitRunner runner,
        @Advice.Argument(0) RunNotifier notifier,
        @Advice.Argument(1) Object test) {
      if (notifier instanceof FailureSuppressingNotifier) {
        // notifier already wrapped, run original method
        return null;
      }

      Description description = MUnitUtils.createDescription(runner, test);
      TestIdentifier testIdentifier = JUnit4Utils.toTestIdentifier(description);
      TestSourceData testSourceData = JUnit4Utils.toTestSourceData(description);

      TestExecutionPolicy executionPolicy =
          TestEventsHandlerHolder.TEST_EVENTS_HANDLER.executionPolicy(
              testIdentifier, testSourceData);
      if (!executionPolicy.applicable()) {
        // retries not applicable, run original method
        return null;
      }

      InstrumentationContext.get(Description.class, TestExecutionHistory.class)
          .put(description, executionPolicy);

      Future<?> result = Future.successful(false);

      FailureSuppressingNotifier failureSuppressingNotifier =
          new FailureSuppressingNotifier(executionPolicy, notifier);
      long duration;
      boolean testFailed;
      do {
        long startTimestamp = System.currentTimeMillis();
        try {
          runTest.setAccessible(true);
          result = (Future<?>) runTest.invoke(runner, failureSuppressingNotifier, test);
          testFailed = failureSuppressingNotifier.getAndResetFailedFlag();
        } catch (Throwable throwable) {
          testFailed = true;
        }
        duration = System.currentTimeMillis() - startTimestamp;
      } while (executionPolicy.retry(!testFailed, duration));

      // skip original method
      return result;
    }

    @SuppressWarnings("bytebuddy-exception-suppression")
    @SuppressFBWarnings(
        value = "UC_USELESS_OBJECT",
        justification = "result is the return value of the original method")
    @Advice.OnMethodExit
    public static void returnResult(
        @Advice.Enter Future<?> overriddenResult,
        @Advice.Return(readOnly = false) Future<?> result) {
      if (overriddenResult != null) {
        result = overriddenResult;
      }
    }
  }
}
