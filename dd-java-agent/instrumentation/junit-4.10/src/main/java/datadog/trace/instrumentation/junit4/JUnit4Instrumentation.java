package datadog.trace.instrumentation.junit4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.rules.RuleChain;
import org.junit.runner.Description;
import org.junit.runner.Runner;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;

@AutoService(InstrumenterModule.class)
public class JUnit4Instrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForTypeHierarchy {

  static final int ORDER = 0;

  public JUnit4Instrumentation() {
    super("ci-visibility", "junit-4");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.junit.runner.Runner";
  }

  @Override
  public int order() {
    return ORDER;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()))
        // do not instrument our internal runner
        // that is used to run instrumentation integration tests
        .and(not(extendsClass(named("datadog.trace.agent.test.SpockRunner"))))
        // do not instrument Karate JUnit 4 runner
        // since Karate has a dedicated instrumentation
        .and(not(extendsClass(named("com.intuit.karate.junit4.Karate"))))
        // PowerMock runner is being instrumented,
        // so do not instrument its internal delegates
        .and(
            not(
                implementsInterface(
                    named(
                        "org.powermock.modules.junit4.common.internal.PowerMockJUnitRunnerDelegate"))));
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
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "org.junit.runner.Description", TestRetryPolicy.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("run").and(takesArgument(0, named("org.junit.runner.notification.RunNotifier"))),
        JUnit4Instrumentation.class.getName() + "$JUnit4Advice");
  }

  public static class JUnit4Advice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void addTracingListener(
        @Advice.This final Runner runner, @Advice.Argument(0) final RunNotifier runNotifier) {
      String runnerClassName = runner.getClass().getName();
      if ("datadog.trace.agent.test.SpockRunner".equals(runnerClassName)
          || runnerClassName.startsWith("com.intuit.karate")
          || runnerClassName.startsWith("io.cucumber")) {
        // checking class names in hierarchyMatcher alone is not enough:
        // for example, Karate calls #run method of its super class,
        // that was transformed
        return;
      }

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

      final TracingListener tracingListener =
          new JUnit4TracingListener(
              InstrumentationContext.get(Description.class, TestRetryPolicy.class));
      runNotifier.addListener(tracingListener);
    }

    // JUnit 4.10 and above
    public static void muzzleCheck(final RuleChain ruleChain) {
      ruleChain.apply(null, null);
    }
  }
}
