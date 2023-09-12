package datadog.trace.instrumentation.junit4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import io.cucumber.core.gherkin.Feature;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;

@AutoService(Instrumenter.class)
public class JUnit4CucumberInstrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForSingleType {

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
      packageName + ".TestEventsHandlerHolder",
      packageName + ".SkippedByItr",
      packageName + ".JUnit4Utils",
      packageName + ".TracingListener",
      packageName + ".CucumberTracingListener",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("childrenInvoker")
            .and(takesArgument(0, named("org.junit.runner.notification.RunNotifier"))),
        JUnit4CucumberInstrumentation.class.getName() + "$CucumberAdvice");
  }

  public static class CucumberAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void addTracingListener(
        @Advice.FieldValue("features") List<Feature> features,
        @Advice.Argument(value = 0, readOnly = false) RunNotifier runNotifier) {

      RunNotifier replacedNotifier = new RunNotifier();

      // copy listeners to new notifier
      List<RunListener> runListeners = JUnit4Utils.runListenersFromRunNotifier(runNotifier);
      if (runListeners != null) {
        for (RunListener listener : runListeners) {
          RunListener unwrappedListener = JUnit4Utils.unwrapListener(listener);
          // skip JUnit 4 listener, we will install Cucumber listener instead
          if (!JUnit4Utils.isTracingListener(unwrappedListener)) {
            replacedNotifier.addListener(listener);
          }
        }
      }

      replacedNotifier.addListener(new CucumberTracingListener(features));
      runNotifier = replacedNotifier;
    }
  }
}
