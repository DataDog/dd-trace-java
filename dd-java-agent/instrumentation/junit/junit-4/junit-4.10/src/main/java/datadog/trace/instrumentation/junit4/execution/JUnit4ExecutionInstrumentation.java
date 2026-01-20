package datadog.trace.instrumentation.junit4.execution;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
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
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.instrumentation.junit4.JUnit4Utils;
import datadog.trace.instrumentation.junit4.TestEventsHandlerHolder;
import datadog.trace.util.Strings;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.rules.RuleChain;
import org.junit.runner.Description;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.ParentRunner;
import org.junit.runners.model.Statement;

@AutoService(InstrumenterModule.class)
public class JUnit4ExecutionInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  private final String parentPackageName = Strings.getPackageName(JUnit4Utils.class.getName());

  public JUnit4ExecutionInstrumentation() {
    super("ci-visibility", "junit-4", "test-retry");
  }

  @Override
  public boolean isEnabled() {
    return Config.get().isCiVisibilityExecutionPoliciesEnabled();
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
        named("runLeaf")
            .and(takesArgument(0, named("org.junit.runners.model.Statement")))
            .and(takesArgument(1, named("org.junit.runner.Description")))
            .and(takesArgument(2, named("org.junit.runner.notification.RunNotifier"))),
        JUnit4ExecutionInstrumentation.class.getName() + "$ExecutionAdvice");
  }

  public static class ExecutionAdvice {
    @SuppressWarnings("bytebuddy-exception-suppression")
    @SuppressFBWarnings("NP_BOOLEAN_RETURN_NULL")
    @Advice.OnMethodEnter(skipOn = Boolean.class)
    public static Boolean apply(
        @Advice.Origin Method runTest,
        @Advice.This ParentRunner<?> runner,
        @Advice.Argument(0) Statement statement,
        @Advice.Argument(1) Description description,
        @Advice.Argument(2) RunNotifier notifier) {
      if (notifier instanceof FailureSuppressingNotifier) {
        // notifier already wrapped, run original method
        return null;
      }

      TestIdentifier testIdentifier = JUnit4Utils.toTestIdentifier(description);
      TestSourceData testSourceData = JUnit4Utils.toTestSourceData(description);
      Collection<String> testTags =
          JUnit4Utils.getCategories(testSourceData.getTestClass(), testSourceData.getTestMethod());
      TestExecutionPolicy executionPolicy =
          TestEventsHandlerHolder.HANDLERS
              .get(TestFrameworkInstrumentation.JUNIT4)
              .executionPolicy(testIdentifier, testSourceData, testTags);
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
          runTest.setAccessible(true);
          runTest.invoke(runner, statement, description, failureSuppressingNotifier);
        } catch (Throwable ignored) {
        }
      } while (executionPolicy.applicable());

      // skip original method
      return Boolean.TRUE;
    }

    // JUnit 4.10 and above
    public static void muzzleCheck(final RuleChain ruleChain) {
      ruleChain.apply(null, null);
    }
  }
}
