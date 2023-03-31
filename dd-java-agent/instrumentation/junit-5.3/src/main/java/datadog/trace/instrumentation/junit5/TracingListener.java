package datadog.trace.instrumentation.junit5;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

public class TracingListener implements TestExecutionListener {

  private final TestEventsHandler testEventsHandler;

  private volatile TestPlan testPlan;

  public TracingListener() {
    Package testPackage = Test.class.getPackage();
    String version = testPackage != null ? testPackage.getImplementationVersion() : null;
    Path currentPath = Paths.get("").toAbsolutePath();
    testEventsHandler =
        InstrumentationBridge.createTestEventsHandler("junit", "junit5", version, currentPath);
  }

  @Override
  public void testPlanExecutionStarted(final TestPlan testPlan) {
    this.testPlan = testPlan;
    testEventsHandler.onTestModuleStart();
  }

  @Override
  public void testPlanExecutionFinished(final TestPlan testPlan) {
    testEventsHandler.onTestModuleFinish();
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
    List<String> tags =
        testIdentifier.getTags().stream().map(TestTag::getName).collect(Collectors.toList());
    testEventsHandler.onTestSuiteStart(testSuiteName, testClass, tags);
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
        testEventsHandler.onTestSuiteSkip(testSuiteName, testClass, reason);

        for (TestIdentifier child : testPlan.getChildren(testIdentifier)) {
          executionSkipped(child, reason);
        }

      } else {
        testEventsHandler.onTestSuiteFailure(testSuiteName, testClass, throwable);
      }
    }

    testEventsHandler.onTestSuiteFinish(testSuiteName, testClass);
  }

  private void testCaseExecutionStarted(final TestIdentifier testIdentifier) {
    TestSource testSource = testIdentifier.getSource().orElse(null);
    if (!(testSource instanceof MethodSource)) {
      return;
    }

    MethodSource methodSource = (MethodSource) testSource;

    String testSuitName = methodSource.getClassName();
    String testName = methodSource.getMethodName();
    String testParameters = JUnit5Utils.getParameters(methodSource, testIdentifier);

    List<String> tags =
        testIdentifier.getTags().stream().map(TestTag::getName).collect(Collectors.toList());

    Class<?> testClass = JUnit5Utils.getTestClass(methodSource);
    Method testMethod = JUnit5Utils.getTestMethod(methodSource);

    testEventsHandler.onTestStart(
        testSuitName, testName, testParameters, tags, testClass, testMethod);
  }

  private void testCaseExecutionFinished(
      final TestIdentifier testIdentifier, final TestExecutionResult testExecutionResult) {
    TestSource testSource = testIdentifier.getSource().orElse(null);
    if (!(testSource instanceof MethodSource)) {
      return;
    }

    MethodSource methodSource = (MethodSource) testSource;
    String testSuiteName = methodSource.getClassName();
    Class<?> testClass = JUnit5Utils.getTestClass(methodSource);
    String testName = methodSource.getMethodName();

    Throwable throwable = testExecutionResult.getThrowable().orElse(null);
    if (throwable != null) {
      if (JUnit5Utils.isAssumptionFailure(throwable)) {
        testEventsHandler.onTestSkip(testSuiteName, testClass, testName, throwable.getMessage());
      } else {
        testEventsHandler.onTestFailure(testSuiteName, testClass, testName, throwable);
      }
    }

    testEventsHandler.onTestFinish(testSuiteName, testClass, testName);
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
    List<String> tags =
        testIdentifier.getTags().stream().map(TestTag::getName).collect(Collectors.toList());

    testEventsHandler.onTestSuiteStart(testSuiteName, testClass, tags);
    testEventsHandler.onTestSuiteSkip(testSuiteName, testClass, reason);

    for (TestIdentifier child : testPlan.getChildren(testIdentifier)) {
      executionSkipped(child, reason);
    }

    testEventsHandler.onTestSuiteFinish(testSuiteName, testClass);
  }

  private void testCaseExecutionSkipped(
      final TestIdentifier testIdentifier, final MethodSource methodSource, final String reason) {
    String testSuiteName = methodSource.getClassName();
    String testName = methodSource.getMethodName();
    String testParameters = JUnit5Utils.getParameters(methodSource, testIdentifier);
    List<String> tags =
        testIdentifier.getTags().stream().map(TestTag::getName).collect(Collectors.toList());

    Class<?> testClass = JUnit5Utils.getTestClass(methodSource);
    Method testMethod = JUnit5Utils.getTestMethod(methodSource);

    testEventsHandler.onTestIgnore(
        testSuiteName, testName, testParameters, tags, testClass, testMethod, reason);
  }
}
