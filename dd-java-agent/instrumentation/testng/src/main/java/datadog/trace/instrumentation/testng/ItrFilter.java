package datadog.trace.instrumentation.testng;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.SkippableTest;
import java.lang.reflect.Method;
import java.util.Set;

public class ItrFilter {

  public static final ItrFilter INSTANCE = new ItrFilter();

  private volatile boolean testsSkipped;

  private ItrFilter() {}

  public boolean skip(Method method, Object instance, Object[] parameters) {
    SkippableTest test = toSkippableTest(method, instance, parameters);
    Set<SkippableTest> skippableTests = Config.get().getCiVisibilitySkippableTests();
    if (skippableTests.contains(test)) {
      testsSkipped = true;
      return true;
    } else {
      return false;
    }
  }

  private SkippableTest toSkippableTest(Method method, Object instance, Object[] parameters) {
    String testSuiteName = instance.getClass().getName();
    String testName = method.getName();
    String testParameters = TestNGUtils.getParameters(parameters);
    return new SkippableTest(testSuiteName, testName, testParameters, null);
  }

  public boolean testsSkipped() {
    return testsSkipped;
  }
}
