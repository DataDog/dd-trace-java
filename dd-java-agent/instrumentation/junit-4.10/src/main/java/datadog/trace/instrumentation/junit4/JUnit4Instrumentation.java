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
import org.junit.runner.Runner;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;

@AutoService(Instrumenter.class)
public class JUnit4Instrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForTypeHierarchy {

  public JUnit4Instrumentation() {
    super("junit", "junit-4");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.junit.runner.Runner";
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
        JUnit4Instrumentation.class.getName() + "$JUnit4Advice");
  }

  public static class JUnit4Advice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void addTracingListener(
        @Advice.This final Runner runner, @Advice.Argument(0) final RunNotifier runNotifier) {
      if (runner.getClass().getName().equals("datadog.trace.agent.test.SpockRunner")) {
        // do not instrument our internal runner
        // that is used to run instrumentation integration tests
        return;
      }

      // No public accessor to get already installed listeners.
      // The installed RunListeners list are obtained using reflection.
      final List<RunListener> runListeners = JUnit4Utils.runListenersFromRunNotifier(runNotifier);
      if (runListeners == null) {
        return;
      }

      for (final RunListener listener : runListeners) {
        RunListener unwrappedListener = JUnit4Utils.unwrapListener(listener);
        // prevents installing TracingListener multiple times
        if (JUnit4Utils.isTracingListener(unwrappedListener)) {
          return;
        }
        // prevents installing TracingListener if we're running in JUnit 5 vintage compatibility
        // mode
        // (in that case JUnit 5 instrumentation will install its own TracingListener)
        if (JUnit4Utils.isJUnitVintageListener(unwrappedListener)) {
          return;
        }
      }

      final TracingListener tracingListener = new TracingListener();
      runNotifier.addListener(tracingListener);
    }

    // JUnit 4.10 and above
    public static void muzzleCheck(final RuleChain ruleChain) {
      ruleChain.apply(null, null);
    }
  }
}
