package datadog.trace.instrumentation.testng;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import org.testng.ITestListener;
import org.testng.ITestNGListener;
import org.testng.TestNG;
import org.testng.annotations.DataProvider;

@AutoService(Instrumenter.class)
public class TestNGInstrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForSingleType {
  public TestNGInstrumentation() {
    super("testng");
  }

  @Override
  public String instrumentedType() {
    return "org.testng.TestNG";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("initializeDefaultListeners"),
        TestNGInstrumentation.class.getName() + "$TestNGAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TestNGUtils",
      packageName + ".TestNGSuiteListener",
      packageName + ".TestNGClassListener",
      packageName + ".TestEventsHandlerHolder",
      packageName + ".TracingListener"
    };
  }

  public static class TestNGAdvice {
    @Advice.OnMethodExit
    public static void addTracingListener(@Advice.This final TestNG testNG) {
      for (final ITestListener testListener : testNG.getTestListeners()) {
        if (testListener instanceof TracingListener) {
          return;
        }
      }

      final String version = TestNGUtils.getTestNGVersion();
      final TracingListener tracingListener = new TracingListener(version);
      testNG.addListener((ITestNGListener) tracingListener);

      TestNGSuiteListener suiteListener = new TestNGSuiteListener(tracingListener);
      testNG.addListener((ITestNGListener) suiteListener);
    }

    // TestNG 6.4 and above
    public static void muzzleCheck(final DataProvider dataProvider) {
      dataProvider.name();
    }
  }
}
