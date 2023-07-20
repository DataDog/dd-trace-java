package datadog.trace.instrumentation.junit5;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

public class TracingListener implements TestExecutionListener {

  private final Map<String, String> versionByTestEngineId = new HashMap<>();

  private volatile TestPlan testPlan;

  public TracingListener(Collection<TestEngine> testEngines) {
    for (TestEngine testEngine : testEngines) {
      String testEngineId = testEngine.getId();
      versionByTestEngineId.put(testEngineId, testEngine.getVersion().orElse(null));
    }
  }

  @Override
  public void testPlanExecutionStarted(final TestPlan testPlan) {
    this.testPlan = testPlan;
  }

  @Override
  public void testPlanExecutionFinished(final TestPlan testPlan) {
    // no op
  }

  @Override
  public void executionStarted(final TestIdentifier testIdentifier) {
    if (testIdentifier.isContainer()) {
      containerExecutionStarted(testIdentifier);
    } else if (testIdentifier.isTest()) {
      testCaseExecutionStarted(testIdentifier);
    }
  }

  @Override
  public void executionFinished(
      final TestIdentifier testIdentifier, final TestExecutionResult testExecutionResult) {
    if (testIdentifier.isContainer()) {
      containerExecutionFinished(testIdentifier, testExecutionResult);
    } else if (testIdentifier.isTest()) {
      testCaseExecutionFinished(testIdentifier, testExecutionResult);
    }
  }

  private void containerExecutionStarted(final TestIdentifier testIdentifier) {
    if (JUnit5Utils.isRootContainer(testIdentifier) || JUnit5Utils.isTestCase(testIdentifier)) {
      return;
    }

    Class<?> testClass = JUnit5Utils.getJavaClass(testIdentifier);
    String testSuiteName =
        testClass != null ? testClass.getName() : testIdentifier.getLegacyReportingName();

    String testEngineId = TestFrameworkUtils.getTestEngineId(testIdentifier);
    String testFramework = getTestFramework(testEngineId);
    String testFrameworkVersion = versionByTestEngineId.get(testEngineId);

    List<String> tags =
        testIdentifier.getTags().stream().map(TestTag::getName).collect(Collectors.toList());
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteStart(
        testSuiteName, testFramework, testFrameworkVersion, testClass, tags, false);
  }

  private void containerExecutionFinished(
      final TestIdentifier testIdentifier, final TestExecutionResult testExecutionResult) {
    if (JUnit5Utils.isRootContainer(testIdentifier) || JUnit5Utils.isTestCase(testIdentifier)) {
      return;
    }

    Class<?> testClass = JUnit5Utils.getJavaClass(testIdentifier);
    String testSuiteName =
        testClass != null ? testClass.getName() : testIdentifier.getLegacyReportingName();

    Throwable throwable = testExecutionResult.getThrowable().orElse(null);
    if (throwable != null) {
      if (JUnit5Utils.isAssumptionFailure(throwable)) {

        String reason = throwable.getMessage();
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteSkip(
            testSuiteName, testClass, reason);

        for (TestIdentifier child : testPlan.getChildren(testIdentifier)) {
          executionSkipped(child, reason);
        }

      } else {
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFailure(
            testSuiteName, testClass, throwable);
      }
    }

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFinish(testSuiteName, testClass);
  }

  private void testCaseExecutionStarted(final TestIdentifier testIdentifier) {
    TestSource testSource = testIdentifier.getSource().orElse(null);
    if (!(testSource instanceof MethodSource)) {
      return;
    }

    MethodSource methodSource = (MethodSource) testSource;

    String testEngineId = TestFrameworkUtils.getTestEngineId(testIdentifier);
    String testFramework = getTestFramework(testEngineId);
    String testFrameworkVersion = versionByTestEngineId.get(testEngineId);

    String testSuitName = methodSource.getClassName();
    String displayName = testIdentifier.getDisplayName();
    String testName = TestFrameworkUtils.getTestName(displayName, methodSource, testEngineId);

    String testParameters = TestFrameworkUtils.getParameters(methodSource, displayName);
    List<String> tags =
        testIdentifier.getTags().stream().map(TestTag::getName).collect(Collectors.toList());

    Class<?> testClass = TestFrameworkUtils.getTestClass(methodSource);
    Method testMethod = TestFrameworkUtils.getTestMethod(methodSource, testEngineId);
    String testMethodName = methodSource.getMethodName();

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestStart(
        testSuitName,
        testName,
        null,
        testFramework,
        testFrameworkVersion,
        testParameters,
        tags,
        testClass,
        testMethodName,
        testMethod);
  }

  private void testCaseExecutionFinished(
      final TestIdentifier testIdentifier, final TestExecutionResult testExecutionResult) {
    TestSource testSource = testIdentifier.getSource().orElse(null);
    if (!(testSource instanceof MethodSource)) {
      return;
    }

    String testEngineId = TestFrameworkUtils.getTestEngineId(testIdentifier);

    MethodSource methodSource = (MethodSource) testSource;
    String testSuiteName = methodSource.getClassName();
    Class<?> testClass = TestFrameworkUtils.getTestClass(methodSource);
    String displayName = testIdentifier.getDisplayName();
    String testName = TestFrameworkUtils.getTestName(displayName, methodSource, testEngineId);
    String testParameters = TestFrameworkUtils.getParameters(methodSource, displayName);

    Throwable throwable = testExecutionResult.getThrowable().orElse(null);
    if (throwable != null) {
      if (JUnit5Utils.isAssumptionFailure(throwable)) {
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSkip(
            testSuiteName, testClass, testName, null, testParameters, throwable.getMessage());
      } else {
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFailure(
            testSuiteName, testClass, testName, null, testParameters, throwable);
      }
    }

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFinish(
        testSuiteName, testClass, testName, null, testParameters);
  }

  @Override
  public void executionSkipped(final TestIdentifier testIdentifier, final String reason) {
    TestSource testSource = testIdentifier.getSource().orElse(null);

    if (testSource instanceof ClassSource) {
      // The annotation @Disabled is kept at type level.
      containerExecutionSkipped(testIdentifier, reason);

    } else if (testSource instanceof MethodSource) {
      // The annotation @Disabled is kept at method level.
      testCaseExecutionSkipped(testIdentifier, (MethodSource) testSource, reason);
    }
  }

  private void containerExecutionSkipped(final TestIdentifier testIdentifier, final String reason) {
    Class<?> testClass = JUnit5Utils.getJavaClass(testIdentifier);
    String testSuiteName =
        testClass != null ? testClass.getName() : testIdentifier.getLegacyReportingName();

    String testEngineId = TestFrameworkUtils.getTestEngineId(testIdentifier);
    String testFramework = getTestFramework(testEngineId);
    String testFrameworkVersion = versionByTestEngineId.get(testEngineId);

    List<String> tags =
        testIdentifier.getTags().stream().map(TestTag::getName).collect(Collectors.toList());

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteStart(
        testSuiteName, testFramework, testFrameworkVersion, testClass, tags, false);
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteSkip(testSuiteName, testClass, reason);

    for (TestIdentifier child : testPlan.getChildren(testIdentifier)) {
      executionSkipped(child, reason);
    }

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFinish(testSuiteName, testClass);
  }

  private void testCaseExecutionSkipped(
      final TestIdentifier testIdentifier, final MethodSource methodSource, final String reason) {
    String testEngineId = TestFrameworkUtils.getTestEngineId(testIdentifier);
    String testFramework = getTestFramework(testEngineId);
    String testFrameworkVersion = versionByTestEngineId.get(testEngineId);

    String testSuiteName = methodSource.getClassName();
    String displayName = testIdentifier.getDisplayName();
    String testName = TestFrameworkUtils.getTestName(displayName, methodSource, testEngineId);

    String testParameters = TestFrameworkUtils.getParameters(methodSource, displayName);
    List<String> tags =
        testIdentifier.getTags().stream().map(TestTag::getName).collect(Collectors.toList());

    Class<?> testClass = TestFrameworkUtils.getTestClass(methodSource);
    Method testMethod = TestFrameworkUtils.getTestMethod(methodSource, testEngineId);
    String testMethodName = methodSource.getMethodName();

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestIgnore(
        testSuiteName,
        testName,
        null,
        testFramework,
        testFrameworkVersion,
        testParameters,
        tags,
        testClass,
        testMethodName,
        testMethod,
        reason);
  }

  private static @Nullable String getTestFramework(String testEngineId) {
    return testEngineId != null && testEngineId.startsWith("junit") ? "junit5" : testEngineId;
  }
}
