package datadog.trace.instrumentation.junit4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
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

@AutoService(Instrumenter.class)
public class JUnit4Instrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForTypeHierarchy {

  public JUnit4Instrumentation() {
    super("ci-visibility", "junit-4");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.junit.runner.Runner";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()))
        // do not instrument our internal runner
        // that is used to run instrumentation integration tests
        .and(not(extendsClass(named("datadog.trace.agent.test.SpockRunner"))));
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
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("run").and(takesArgument(0, named("org.junit.runner.notification.RunNotifier"))),
        JUnit4Instrumentation.class.getName() + "$JUnit4Advice");
  }

  public static class JUnit4Advice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void addTracingListener(@Advice.Argument(0) final RunNotifier runNotifier) {
      // No public accessor to get already installed listeners.
      // The installed RunListeners list are obtained using reflection.
      final List<RunListener> runListeners = JUnit4Utils.runListenersFromRunNotifier(runNotifier);
      if (runListeners == null) {
        return;
      }

      for (final RunListener listener : runListeners) {
        RunListener tracingListener = JUnit4Utils.toTracingListener(listener);
        if (tracingListener != null) {
          // prevents installing TracingListener multiple times
          return;
        }
      }

      final TracingListener tracingListener = new JUnit4TracingListener();
      runNotifier.addListener(tracingListener);
    }

    // JUnit 4.10 and above
    public static void muzzleCheck(final RuleChain ruleChain) {
      ruleChain.apply(null, null);
    }
  }
}
