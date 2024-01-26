package datadog.trace.instrumentation.testng;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.instrumentation.testng.retry.RetryAnnotationTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
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
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        MethodDescription::isConstructor, TestNGInstrumentation.class.getName() + "$TestNGAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TestNGUtils",
      packageName + ".TestNGSuiteListener",
      packageName + ".TestNGClassListener",
      packageName + ".TestEventsHandlerHolder",
      packageName + ".TracingListener",
      packageName + ".retry.RetryAnalyzer",
      packageName + ".retry.RetryAnnotationTransformer",
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

      final TracingListener tracingListener = new TracingListener();
      testNG.addListener((ITestNGListener) tracingListener);

      TestNGSuiteListener suiteListener = new TestNGSuiteListener(tracingListener);
      testNG.addListener((ITestNGListener) suiteListener);

      if (Config.get().isCiVisibilityFlakyRetryEnabled()) {
        final RetryAnnotationTransformer transformer =
            new RetryAnnotationTransformer(testNG.getAnnotationTransformer());
        testNG.addListener(transformer);
      }
    }

    // TestNG 6.4 and above
    public static void muzzleCheck(final DataProvider dataProvider) {
      dataProvider.name();
    }
  }
}
