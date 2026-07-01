package datadog.trace.instrumentation.junit4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.junit.runner.Description;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;

/**
 * Restores suite lifecycle events when JUnit 4.13+ tests run under Bazel's {@code
 * com.google.testing.junit.runner.BazelTestRunner}.
 *
 * <p>Bazel's {@code com.google.testing.junit.junit4.runner.RunNotifierWrapper} explicitly delegates
 * {@code addListener}, {@code fireTestStarted}, etc. to the inner notifier, but does not override
 * {@link RunNotifier#fireTestSuiteStarted(Description)} and {@link
 * RunNotifier#fireTestSuiteFinished(Description)}. The runner therefore fires the suite-lifecycle
 * events on the wrapper's own (always empty) listener list, and our tracing listener — installed on
 * the inner notifier via the wrapper's delegating {@code addListener} — never sees them.
 *
 * <p>This advice intercepts {@link RunNotifier#fireTestSuiteStarted(Description)} and {@link
 * RunNotifier#fireTestSuiteFinished(Description)} on the wrapper instance, looks up our tracing
 * listener inside the inner notifier's listener list, and invokes it directly. Other listeners
 * installed on the inner notifier are intentionally skipped so Bazel's default dispatch behavior is
 * unchanged. Calls on a non-wrapper notifier are a no-op.
 */
@AutoService(InstrumenterModule.class)
public class BazelRunNotifierWrapperInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public BazelRunNotifierWrapperInstrumentation() {
    super("ci-visibility", "junit-4");
  }

  @Override
  public String instrumentedType() {
    return "org.junit.runner.notification.RunNotifier";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JUnit4Utils",
      packageName + ".TracingListener",
      packageName + ".SkippedByDatadog",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("fireTestSuiteStarted").and(takesArgument(0, named("org.junit.runner.Description"))),
        BazelRunNotifierWrapperInstrumentation.class.getName() + "$FireSuiteStartedAdvice");
    transformer.applyAdvice(
        named("fireTestSuiteFinished").and(takesArgument(0, named("org.junit.runner.Description"))),
        BazelRunNotifierWrapperInstrumentation.class.getName() + "$FireSuiteFinishedAdvice");
  }

  public static class FireSuiteStartedAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void fireOnTracingListener(
        @Advice.This final RunNotifier self, @Advice.Argument(0) final Description description) {
      RunNotifier inner = JUnit4Utils.unwrapRunNotifier(self);
      if (inner == null || inner == self) {
        return;
      }
      List<RunListener> listeners = JUnit4Utils.runListenersFromRunNotifier(inner);
      if (listeners == null) {
        return;
      }
      for (RunListener listener : listeners) {
        TracingListener tracingListener = JUnit4Utils.toTracingListener(listener);
        if (tracingListener != null) {
          tracingListener.testSuiteStarted(description);
        }
      }
    }

    // JUnit 4.13 muzzle marker: fireTestSuiteStarted exists from 4.13.
    public static void muzzleCheck(final RunNotifier notifier) {
      notifier.fireTestSuiteStarted(null);
    }
  }

  public static class FireSuiteFinishedAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void fireOnTracingListener(
        @Advice.This final RunNotifier self, @Advice.Argument(0) final Description description) {
      RunNotifier inner = JUnit4Utils.unwrapRunNotifier(self);
      if (inner == null || inner == self) {
        return;
      }
      List<RunListener> listeners = JUnit4Utils.runListenersFromRunNotifier(inner);
      if (listeners == null) {
        return;
      }
      for (RunListener listener : listeners) {
        TracingListener tracingListener = JUnit4Utils.toTracingListener(listener);
        if (tracingListener != null) {
          tracingListener.testSuiteFinished(description);
        }
      }
    }

    public static void muzzleCheck(final RunNotifier notifier) {
      notifier.fireTestSuiteFinished(null);
    }
  }
}
