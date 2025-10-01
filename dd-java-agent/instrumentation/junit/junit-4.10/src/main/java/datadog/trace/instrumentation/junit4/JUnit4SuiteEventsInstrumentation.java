package datadog.trace.instrumentation.junit4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.rules.RuleChain;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.ParentRunner;

@AutoService(InstrumenterModule.class)
public class JUnit4SuiteEventsInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public JUnit4SuiteEventsInstrumentation() {
    super("ci-visibility", "junit-4");
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
      packageName + ".TestEventsHandlerHolder",
      packageName + ".SkippedByDatadog",
      packageName + ".JUnit4Utils",
      packageName + ".TracingListener",
      packageName + ".JUnit4TracingListener",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("run")
            .and(
                takesArgument(
                    0,
                    named("org.junit.runner.notification.RunNotifier")
                        .and(not(declaresMethod(named("fireTestSuiteStarted")))))),
        JUnit4SuiteEventsInstrumentation.class.getName() + "$JUnit4SuiteEventsAdvice");
  }

  public static class JUnit4SuiteEventsAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void fireSuiteStartedEvent(
        @Advice.Argument(0) final RunNotifier runNotifier,
        @Advice.This final ParentRunner<?> runner) {
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
