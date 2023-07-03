package datadog.trace.instrumentation.junit4;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.SkippableTest;
import java.lang.reflect.Method;
import java.util.Set;
import org.junit.runner.Description;

public class ItrFilter {

  public static final ItrFilter INSTANCE = new ItrFilter();

  private volatile boolean testsSkipped;

  private ItrFilter() {}

  public boolean skip(Description description) {
    SkippableTest test = toSkippableTest(description);
    Set<SkippableTest> skippableTests = Config.get().getCiVisibilitySkippableTests();
    if (skippableTests.contains(test)) {
      testsSkipped = true;
      return true;
    } else {
      return false;
    }
  }

  private SkippableTest toSkippableTest(Description description) {
    Method testMethod = JUnit4Utils.getTestMethod(description);
    String suite = description.getClassName();
    String name = JUnit4Utils.getTestName(description, testMethod);
    String parameters = JUnit4Utils.getParameters(description);
    return new SkippableTest(suite, name, parameters, null);
  }

  public boolean testsSkipped() {
    return testsSkipped;
  }
}
