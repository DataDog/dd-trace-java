package datadog.trace.instrumentation.junit5;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.SkippableTest;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.MethodSource;

public class ItrPredicate implements Predicate<TestDescriptor> {

  public static final ItrPredicate INSTANCE = new ItrPredicate();

  private final Set<SkippableTest> skippableTests = Config.get().getCiVisibilitySkippableTests();
  private volatile boolean testsSkipped;

  private ItrPredicate() {}

  @Override
  public boolean test(TestDescriptor testDescriptor) {
    SkippableTest test = toSkippableTest(testDescriptor);
    if (skippableTests.contains(test)) {
      testsSkipped = true;
      return false;
    } else {
      return true;
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
    String testName = ItrUtils.getTestName(methodSource, displayName, testEngineId);

    String testParameters = ItrUtils.getParameters(methodSource, displayName);

    return new SkippableTest(testSuitName, testName, testParameters, null);
  }

  public boolean testsSkipped() {
    return testsSkipped;
  }
}
