package datadog.trace.instrumentation.testng;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.DDTest;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.instrumentation.testng.execution.RetryAnnotationTransformer;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import org.testng.ITestListener;
import org.testng.ITestNGListener;
import org.testng.ITestResult;
import org.testng.TestNG;
import org.testng.annotations.DataProvider;

@AutoService(InstrumenterModule.class)
public class TestNGInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public static final int ORDER = 0;

  public TestNGInstrumentation() {
    super("testng");
  }

  @Override
  public String instrumentedType() {
    return "org.testng.TestNG";
  }

  @Override
  public int order() {
    return ORDER;
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
      packageName + ".execution.RetryAnalyzer",
      packageName + ".execution.RetryAnnotationTransformer",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "org.testng.ITestResult", "datadog.trace.api.civisibility.DDTest");
  }

  public static class TestNGAdvice {
    @Advice.OnMethodExit
    public static void addTracingListener(@Advice.This final TestNG testNG) {
      for (final ITestListener testListener : testNG.getTestListeners()) {
        if (testListener instanceof TracingListener) {
          return;
        }
      }

      ContextStore<ITestResult, DDTest> contextStore =
          InstrumentationContext.get(ITestResult.class, DDTest.class);
      TestEventsHandlerHolder.setContextStore(contextStore);
      TestEventsHandlerHolder.start();

      final TracingListener tracingListener = new TracingListener();
      testNG.addListener((ITestNGListener) tracingListener);

      TestNGSuiteListener suiteListener = new TestNGSuiteListener(tracingListener);
      testNG.addListener((ITestNGListener) suiteListener);

      if (Config.get().isCiVisibilityExecutionPoliciesEnabled()) {
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
