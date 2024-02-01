package datadog.trace.instrumentation.junit4.retry;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.instrumentation.junit4.CucumberUtils;
import datadog.trace.instrumentation.junit4.JUnit4Utils;
import datadog.trace.instrumentation.junit4.TestEventsHandlerHolder;
import datadog.trace.util.Strings;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.invoke.MethodHandle;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import org.junit.runner.Description;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.ParentRunner;

@AutoService(Instrumenter.class)
public class Cucumber4RetryInstrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForSingleType {

  private final String parentPackageName = Strings.getPackageName(JUnit4Utils.class.getName());

  public Cucumber4RetryInstrumentation() {
    super("ci-visibility", "junit-4", "junit-4-cucumber", "test-retry");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems) && Config.get().isCiVisibilityFlakyRetryEnabled();
  }

  @Override
  public String instrumentedType() {
    return "io.cucumber.junit.FeatureRunner";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      parentPackageName + ".SkippedByItr",
      parentPackageName + ".CucumberUtils",
      parentPackageName + ".JUnit4Utils",
      parentPackageName + ".TracingListener",
      parentPackageName + ".TestEventsHandlerHolder",
      packageName + ".RetryAwareNotifier",
    };
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
        Cucumber4RetryInstrumentation.class.getName() + "$RetryAdvice");
  }

  public static class RetryAdvice {
    @SuppressFBWarnings("NP_BOOLEAN_RETURN_NULL")
    @Advice.OnMethodEnter(skipOn = Boolean.class)
    public static Boolean retryIfNeeded(
        @Advice.SelfCallHandle(bound = false) MethodHandle runPickle,
        @Advice.This ParentRunner<?> /* io.cucumber.junit.FeatureRunner */ featureRunner,
        @Advice.Argument(0) Object /* io.cucumber.junit.PickleRunners.PickleRunner */ pickleRunner,
        @Advice.Argument(1) RunNotifier notifier) {
      if (notifier instanceof RetryAwareNotifier) {
        // notifier already wrapped, run original method
        return null;
      }

      Description description = CucumberUtils.getPickleRunnerDescription(pickleRunner);
      TestIdentifier testIdentifier = CucumberUtils.toTestIdentifier(description);
      TestRetryPolicy retryPolicy =
          TestEventsHandlerHolder.TEST_EVENTS_HANDLER.retryPolicy(testIdentifier);
      if (!retryPolicy.retryPossible()) {
        // retries not applicable, run original method
        return null;
      }

      RetryAwareNotifier retryAwareNotifier = new RetryAwareNotifier(retryPolicy, notifier);
      boolean testFailed;
      do {
        try {
          runPickle.invokeWithArguments(featureRunner, pickleRunner, retryAwareNotifier);
          testFailed = retryAwareNotifier.getAndResetFailedFlag();
        } catch (Throwable throwable) {
          testFailed = true;
        }
      } while (retryPolicy.retry(!testFailed));

      // skip original method
      return Boolean.TRUE;
    }
  }
}
