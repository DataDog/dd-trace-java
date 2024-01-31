package datadog.trace.instrumentation.junit4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.config.TestIdentifier;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.Ignore;
import org.junit.rules.RuleChain;
import org.junit.runner.Description;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.ParentRunner;

@AutoService(Instrumenter.class)
public class JUnit4ItrInstrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForTypeHierarchy {

  public JUnit4ItrInstrumentation() {
    super("ci-visibility", "junit-4");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems) && Config.get().isCiVisibilityItrEnabled();
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.junit.runners.ParentRunner";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()))
        // ITR skipping for Cucumber is done in a dedicated instrumentation
        .and(not(extendsClass(named("io.cucumber.junit.FeatureRunner"))));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TestEventsHandlerHolder",
      packageName + ".SkippedByItr",
      packageName + ".JUnit4Utils",
      packageName + ".TracingListener",
      packageName + ".JUnit4TracingListener",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("runChild")
            .and(takesArguments(2))
            .and(takesArgument(1, named("org.junit.runner.notification.RunNotifier"))),
        JUnit4ItrInstrumentation.class.getName() + "$JUnit4ItrInstrumentationAdvice");
  }

  public static class JUnit4ItrInstrumentationAdvice {
    @SuppressFBWarnings("NP_BOOLEAN_RETURN_NULL")
    @Advice.OnMethodEnter(skipOn = Boolean.class)
    public static Boolean runChild(
        @Advice.This ParentRunner<?> runner,
        @Advice.Argument(0) Object child,
        @Advice.Argument(1) RunNotifier notifier) {
      Description description = JUnit4Utils.getDescription(runner, child);
      if (description == null || !description.isTest()) {
        // ITR only skips individual tests
        return null;
      }

      Ignore ignoreAnnotation = description.getAnnotation(Ignore.class);
      if (ignoreAnnotation != null) {
        // class is ignored, ITR not applicable
        return null;
      }

      Class<?> testClass = description.getTestClass();
      Method testMethod = JUnit4Utils.getTestMethod(description);
      List<String> categories = JUnit4Utils.getCategories(testClass, testMethod);
      for (String category : categories) {
        if (category.endsWith(InstrumentationBridge.ITR_UNSKIPPABLE_TAG)) {
          return null;
        }
      }

      TestIdentifier test = JUnit4Utils.toTestIdentifier(description, true);
      if (TestEventsHandlerHolder.TEST_EVENTS_HANDLER.skip(test)) {
        Description skippedDescription = JUnit4Utils.getSkippedDescription(description);
        notifier.fireTestIgnored(skippedDescription);
        return Boolean.FALSE;
      } else {
        return null;
      }
    }

    // JUnit 4.10 and above
    public static void muzzleCheck(final RuleChain ruleChain) {
      ruleChain.apply(null, null);
    }
  }
}
