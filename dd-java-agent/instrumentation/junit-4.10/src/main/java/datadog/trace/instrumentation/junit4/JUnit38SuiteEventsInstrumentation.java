package datadog.trace.instrumentation.junit4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.List;
import junit.framework.TestCase;
import net.bytebuddy.asm.Advice;
import org.junit.rules.RuleChain;
import org.junit.runner.Runner;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;

/** Supports suite started/finished events for {@link TestCase} subclasses. */
@AutoService(Instrumenter.class)
public class JUnit38SuiteEventsInstrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForSingleType {

  public JUnit38SuiteEventsInstrumentation() {
    super("ci-visibility", "junit-4", "junit-38");
  }

  @Override
  public String instrumentedType() {
    return "org.junit.internal.runners.JUnit38ClassRunner";
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
        named("run").and(takesArgument(0, named("org.junit.runner.notification.RunNotifier"))),
        JUnit38SuiteEventsInstrumentation.class.getName() + "$JUnit38SuiteEventsAdvice");
  }

  public static class JUnit38SuiteEventsAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void fireSuiteStartedEvent(
        @Advice.Argument(0) final RunNotifier runNotifier, @Advice.This final Runner runner) {
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
        @Advice.Argument(0) final RunNotifier runNotifier, @Advice.This final Runner runner) {
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
