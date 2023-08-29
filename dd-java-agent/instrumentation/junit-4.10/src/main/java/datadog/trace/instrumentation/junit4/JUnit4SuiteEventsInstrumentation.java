package datadog.trace.instrumentation.junit4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.rules.RuleChain;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.ParentRunner;

@AutoService(Instrumenter.class)
public class JUnit4SuiteEventsInstrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForTypeHierarchy {

  public JUnit4SuiteEventsInstrumentation() {
    super("junit-4-suite-events");
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
      packageName + ".SkippedByItr",
      packageName + ".JUnit4Utils$Cucumber",
      packageName + ".JUnit4Utils$Munit",
      packageName + ".JUnit4Utils",
      packageName + ".TestEventsHandlerHolder",
      packageName + ".TracingListener",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("run").and(takesArgument(0, named("org.junit.runner.notification.RunNotifier"))),
        JUnit4SuiteEventsInstrumentation.class.getName() + "$JUnit4SuiteEventsAdvice");
  }

  public static class JUnit4SuiteEventsAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void fireSuiteStartedEvent(
        @Advice.Argument(0) final RunNotifier runNotifier,
        @Advice.This final ParentRunner<?> runner) {
      if (JUnit4Utils.NATIVE_SUITE_EVENTS_SUPPORTED) {
        return;
      }

      final List<RunListener> runListeners = JUnit4Utils.runListenersFromRunNotifier(runNotifier);
      if (runListeners == null) {
        return;
      }

      for (final RunListener listener : runListeners) {
        TracingListener tracingListener = JUnit4Utils.toTracingListener(listener);
        if (tracingListener != null) {
          tracingListener.testSuiteStarted(runner.getDescription());
        }
      }
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void fireSuiteFinishedEvent(
        @Advice.Argument(0) final RunNotifier runNotifier,
        @Advice.This final ParentRunner<?> runner) {
      if (JUnit4Utils.NATIVE_SUITE_EVENTS_SUPPORTED) {
        return;
      }

      final List<RunListener> runListeners = JUnit4Utils.runListenersFromRunNotifier(runNotifier);
      if (runListeners == null) {
        return;
      }

      for (final RunListener listener : runListeners) {
        TracingListener tracingListener = JUnit4Utils.toTracingListener(listener);
        if (tracingListener != null) {
          tracingListener.testSuiteFinished(runner.getDescription());
        }
      }
    }

    // JUnit 4.10 and above
    public static void muzzleCheck(final RuleChain ruleChain) {
      ruleChain.apply(null, null);
    }
  }
}
