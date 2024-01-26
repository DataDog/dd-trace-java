package datadog.trace.instrumentation.junit4.retry;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.instrumentation.junit4.JUnit4Utils;
import datadog.trace.instrumentation.junit4.TestEventsHandlerHolder;
import datadog.trace.util.Strings;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.reflect.Method;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.rules.RuleChain;
import org.junit.runner.Description;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.ParentRunner;
import org.junit.runners.model.Statement;

@AutoService(Instrumenter.class)
public class JUnit4RetryInstrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForTypeHierarchy {

  private final String parentPackageName = Strings.getPackageName(JUnit4Utils.class.getName());

  public JUnit4RetryInstrumentation() {
    super("ci-visibility", "junit-4", "test-retry");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems) && Config.get().isCiVisibilityFlakyRetryEnabled();
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.junit.runners.ParentRunner";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      parentPackageName + ".SkippedByItr",
      parentPackageName + ".JUnit4Utils",
      parentPackageName + ".TracingListener",
      parentPackageName + ".TestEventsHandlerHolder",
      packageName + ".RetryAwareNotifier"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("runLeaf")
            .and(takesArgument(0, named("org.junit.runners.model.Statement")))
            .and(takesArgument(1, named("org.junit.runner.Description")))
            .and(takesArgument(2, named("org.junit.runner.notification.RunNotifier"))),
        JUnit4RetryInstrumentation.class.getName() + "$RetryAdvice");
  }

  public static class RetryAdvice {
    @SuppressFBWarnings("NP_BOOLEAN_RETURN_NULL")
    @Advice.OnMethodEnter(skipOn = Boolean.class)
    public static Boolean retryIfNeeded(
        @Advice.Origin Method runTest,
        @Advice.This ParentRunner<?> runner,
        @Advice.Argument(0) Statement statement,
        @Advice.Argument(1) Description description,
        @Advice.Argument(2) RunNotifier notifier) {
      if (notifier instanceof RetryAwareNotifier) {
        // notifier already wrapped, run original method
        return null;
      }

      TestIdentifier testIdentifier = JUnit4Utils.toTestIdentifier(description, false);
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
          runTest.setAccessible(true);
          runTest.invoke(runner, statement, description, retryAwareNotifier);
          testFailed = retryAwareNotifier.getAndResetFailedFlag();
        } catch (Throwable throwable) {
          testFailed = true;
        }
      } while (retryPolicy.retry(!testFailed));

      // skip original method
      return Boolean.TRUE;
    }

    // JUnit 4.10 and above
    public static void muzzleCheck(final RuleChain ruleChain) {
      ruleChain.apply(null, null);
    }
  }
}
