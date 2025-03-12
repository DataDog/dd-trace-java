package datadog.trace.instrumentation.junit4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
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

@AutoService(InstrumenterModule.class)
public class MUnitInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public MUnitInstrumentation() {
    super("ci-visibility", "junit-4", "junit-4-munit");
  }

  @Override
  public String instrumentedType() {
    return "munit.MUnitRunner";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TestEventsHandlerHolder",
      packageName + ".SkippedByDatadog",
      packageName + ".JUnit4Utils",
      packageName + ".MUnitUtils",
      packageName + ".TracingListener",
      packageName + ".MUnitTracingListener",
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
        named("run").and(takesArgument(0, named("org.junit.runner.notification.RunNotifier"))),
        MUnitInstrumentation.class.getName() + "$MUnitAdvice");
  }

  public static class MUnitAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void addTracingListener(
        @Advice.Argument(value = 0, readOnly = false) RunNotifier runNotifier) {

      RunNotifier replacedNotifier = new RunNotifier();

      // copy listeners to new notifier
      List<RunListener> runListeners = JUnit4Utils.runListenersFromRunNotifier(runNotifier);
      if (runListeners != null) {
        for (RunListener listener : runListeners) {
          RunListener tracingListener = JUnit4Utils.toTracingListener(listener);
          // skip JUnit 4 listener, we will install MUnit listener instead
          if (tracingListener == null) {
            replacedNotifier.addListener(listener);
          }
        }
      }

      TestEventsHandlerHolder.start(TestFrameworkInstrumentation.MUNIT, MUnitUtils.CAPABILITIES);

      replacedNotifier.addListener(
          new MUnitTracingListener(
              InstrumentationContext.get(Description.class, TestExecutionHistory.class)));
      runNotifier = replacedNotifier;
    }
  }
}
