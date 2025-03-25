package datadog.trace.instrumentation.junit4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.civisibility.execution.TestExecutionHistory;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.junit.runner.Description;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.ParentRunner;

@AutoService(InstrumenterModule.class)
public class JUnit4CucumberInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public JUnit4CucumberInstrumentation() {
    super("ci-visibility", "junit-4", "junit-4-cucumber");
  }

  @Override
  public String instrumentedType() {
    return "io.cucumber.junit.Cucumber";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".CucumberUtils",
      packageName + ".TestEventsHandlerHolder",
      packageName + ".SkippedByDatadog",
      packageName + ".JUnit4Utils",
      packageName + ".TracingListener",
      packageName + ".CucumberTracingListener",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "org.junit.runner.Description", TestExecutionHistory.class.getName());
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return CucumberUtils.MuzzleHelper.additionalMuzzleReferences();
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("childrenInvoker")
            .and(takesArgument(0, named("org.junit.runner.notification.RunNotifier"))),
        JUnit4CucumberInstrumentation.class.getName() + "$CucumberAdvice");
  }

  public static class CucumberAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void addTracingListener(
        @Advice.FieldValue("children") List<ParentRunner<?>> children,
        @Advice.Argument(value = 0, readOnly = false) RunNotifier runNotifier) {

      RunNotifier replacedNotifier = new RunNotifier();

      // copy listeners to new notifier
      List<RunListener> runListeners = JUnit4Utils.runListenersFromRunNotifier(runNotifier);
      if (runListeners != null) {
        for (RunListener listener : runListeners) {
          RunListener tracingListener = JUnit4Utils.toTracingListener(listener);
          // skip JUnit 4 listener, we will install Cucumber listener instead
          if (tracingListener == null) {
            replacedNotifier.addListener(listener);
          }
        }
      }

      TestEventsHandlerHolder.start(
          TestFrameworkInstrumentation.CUCUMBER, CucumberUtils.CAPABILITIES);

      replacedNotifier.addListener(
          new CucumberTracingListener(
              InstrumentationContext.get(Description.class, TestExecutionHistory.class), children));
      runNotifier = replacedNotifier;
    }
  }
}
