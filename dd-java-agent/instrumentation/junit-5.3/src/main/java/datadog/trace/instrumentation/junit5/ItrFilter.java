package datadog.trace.instrumentation.junit5;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.SkippableTest;
import java.util.Set;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.MethodSource;

public class ItrFilter {

  public static final ItrFilter INSTANCE = new ItrFilter();

  private volatile boolean testsSkipped;

  private ItrFilter() {}

  public boolean skip(TestDescriptor testDescriptor) {
    SkippableTest test = toSkippableTest(testDescriptor);
    Set<SkippableTest> skippableTests = Config.get().getCiVisibilitySkippableTests();
    if (skippableTests.contains(test)) {
      testsSkipped = true;
      return true;
    } else {
      return false;
    }
  }

  private SkippableTest toSkippableTest(TestDescriptor testDescriptor) {
    TestSource testSource = testDescriptor.getSource().orElse(null);
    if (!(testSource instanceof MethodSource)) {
      return null;
    }

    MethodSource methodSource = (MethodSource) testSource;
    String testSuitName = methodSource.getClassName();

    String displayName = testDescriptor.getDisplayName();
    UniqueId uniqueId = testDescriptor.getUniqueId();
    String testEngineId = uniqueId.getEngineId().orElse(null);
    String testName = TestFrameworkUtils.getTestName(displayName, methodSource, testEngineId);

    String testParameters = TestFrameworkUtils.getParameters(methodSource, displayName);

    return new SkippableTest(testSuitName, testName, testParameters, null);
  }

  public boolean testsSkipped() {
    return testsSkipped;
  }
}
