package datadog.trace.instrumentation.junit5;

import datadog.trace.api.Pair;
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
import org.junit.platform.engine.support.descriptor.ClasspathResourceSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

public class TracingListener implements TestExecutionListener {

  private final Map<String, String> versionByTestEngineId = new HashMap<>();

  private volatile TestPlan testPlan;

  public TracingListener(Collection<TestEngine> testEngines) {
    for (TestEngine testEngine : testEngines) {
      String engineId = testEngine.getId();
      String engineVersion =
          !JUnitPlatformUtils.Cucumber.ENGINE_ID.equals(engineId)
              ? testEngine.getVersion().orElse(null)
              : JUnitPlatformUtils.Cucumber.getCucumberVersion(testEngine);
      versionByTestEngineId.put(engineId, engineVersion);
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
    if (!JUnitPlatformLauncherUtils.isSuite(testIdentifier)) {
      return;
    }

    Class<?> testClass = JUnitPlatformLauncherUtils.getJavaClass(testIdentifier);
    String testSuiteName =
        testClass != null ? testClass.getName() : testIdentifier.getLegacyReportingName();

    String testEngineId = JUnitPlatformLauncherUtils.getTestEngineId(testIdentifier);
    String testFramework = getTestFramework(testEngineId);
    String testFrameworkVersion = versionByTestEngineId.get(testEngineId);

    List<String> tags =
        testIdentifier.getTags().stream().map(TestTag::getName).collect(Collectors.toList());
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteStart(
        testSuiteName, testFramework, testFrameworkVersion, testClass, tags, false);
  }

  private void containerExecutionFinished(
      final TestIdentifier testIdentifier, final TestExecutionResult testExecutionResult) {
    if (!JUnitPlatformLauncherUtils.isSuite(testIdentifier)) {
      return;
    }

    Class<?> testClass = JUnitPlatformLauncherUtils.getJavaClass(testIdentifier);
    String testSuiteName =
        testClass != null ? testClass.getName() : testIdentifier.getLegacyReportingName();

    Throwable throwable = testExecutionResult.getThrowable().orElse(null);
    if (throwable != null) {
      if (JUnitPlatformUtils.isAssumptionFailure(throwable)) {

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
    if (testSource instanceof MethodSource) {
      testMethodExecutionStarted(testIdentifier, (MethodSource) testSource);

    } else if (testSource instanceof ClasspathResourceSource) {
      testResourceExecutionStarted(testIdentifier, (ClasspathResourceSource) testSource);
    }
  }

  private void testMethodExecutionStarted(TestIdentifier testIdentifier, MethodSource testSource) {
    String testEngineId = JUnitPlatformLauncherUtils.getTestEngineId(testIdentifier);
    String testFramework = getTestFramework(testEngineId);
    String testFrameworkVersion = versionByTestEngineId.get(testEngineId);

    String testSuitName = testSource.getClassName();
    String displayName = testIdentifier.getDisplayName();
    String testName = JUnitPlatformUtils.getTestName(displayName, testSource, testEngineId);

    String testParameters = JUnitPlatformUtils.getParameters(testSource, displayName);
    List<String> tags =
        testIdentifier.getTags().stream().map(TestTag::getName).collect(Collectors.toList());

    Class<?> testClass = JUnitPlatformUtils.getTestClass(testSource);
    Method testMethod = JUnitPlatformUtils.getTestMethod(testSource, testEngineId);
    String testMethodName = testSource.getMethodName();

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

  private void testResourceExecutionStarted(
      TestIdentifier testIdentifier, ClasspathResourceSource testSource) {
    String testEngineId = JUnitPlatformLauncherUtils.getTestEngineId(testIdentifier);
    if (!JUnitPlatformUtils.Cucumber.ENGINE_ID.equals(testEngineId)) {
      return;
    }

    String classpathResourceName = testSource.getClasspathResourceName();

    Pair<String, String> names =
        JUnitPlatformLauncherUtils.Cucumber.getFeatureAndScenarioNames(
            testPlan, testIdentifier, classpathResourceName);
    String testSuiteName = names.getLeft();
    String testName = names.getRight();

    String testFramework = getTestFramework(testEngineId);
    String testFrameworkVersion = versionByTestEngineId.get(testEngineId);

    List<String> tags =
        testIdentifier.getTags().stream().map(TestTag::getName).collect(Collectors.toList());

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestStart(
        testSuiteName,
        testName,
        null,
        testFramework,
        testFrameworkVersion,
        null,
        tags,
        null,
        null,
        null);
  }

  private void testCaseExecutionFinished(
      final TestIdentifier testIdentifier, final TestExecutionResult testExecutionResult) {
    TestSource testSource = testIdentifier.getSource().orElse(null);
    if (testSource instanceof MethodSource) {
      testMethodExecutionFinished(testIdentifier, testExecutionResult, (MethodSource) testSource);

    } else if (testSource instanceof ClasspathResourceSource) {
      testResourceExecutionFinished(
          testIdentifier, testExecutionResult, (ClasspathResourceSource) testSource);
    }
  }

  private static void testMethodExecutionFinished(
      TestIdentifier testIdentifier,
      TestExecutionResult testExecutionResult,
      MethodSource testSource) {
    String testEngineId = JUnitPlatformLauncherUtils.getTestEngineId(testIdentifier);

    String testSuiteName = testSource.getClassName();
    Class<?> testClass = JUnitPlatformUtils.getTestClass(testSource);
    String displayName = testIdentifier.getDisplayName();
    String testName = JUnitPlatformUtils.getTestName(displayName, testSource, testEngineId);
    String testParameters = JUnitPlatformUtils.getParameters(testSource, displayName);

    Throwable throwable = testExecutionResult.getThrowable().orElse(null);
    if (throwable != null) {
      if (JUnitPlatformUtils.isAssumptionFailure(throwable)) {
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

  private void testResourceExecutionFinished(
      TestIdentifier testIdentifier,
      TestExecutionResult testExecutionResult,
      ClasspathResourceSource testSource) {
    String testEngineId = JUnitPlatformLauncherUtils.getTestEngineId(testIdentifier);
    if (!JUnitPlatformUtils.Cucumber.ENGINE_ID.equals(testEngineId)) {
      return;
    }

    String classpathResourceName = testSource.getClasspathResourceName();

    Pair<String, String> names =
        JUnitPlatformLauncherUtils.Cucumber.getFeatureAndScenarioNames(
            testPlan, testIdentifier, classpathResourceName);
    String testSuiteName = names.getLeft();
    String testName = names.getRight();

    Throwable throwable = testExecutionResult.getThrowable().orElse(null);
    if (throwable != null) {
      if (JUnitPlatformUtils.isAssumptionFailure(throwable)) {
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSkip(
            testSuiteName, null, testName, null, null, throwable.getMessage());
      } else {
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFailure(
            testSuiteName, null, testName, null, null, throwable);
      }
    }

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFinish(
        testSuiteName, null, testName, null, null);
  }

  @Override
  public void executionSkipped(final TestIdentifier testIdentifier, final String reason) {
    TestSource testSource = testIdentifier.getSource().orElse(null);

    if (testSource instanceof ClassSource) {
      // The annotation @Disabled is kept at type level.
      containerExecutionSkipped(testIdentifier, reason);

    } else if (testSource instanceof MethodSource) {
      // The annotation @Disabled is kept at method level.
      testMethodExecutionSkipped(testIdentifier, (MethodSource) testSource, reason);

    } else if (testSource instanceof ClasspathResourceSource) {
      testResourceExecutionSkipped(testIdentifier, (ClasspathResourceSource) testSource, reason);
    }
  }

  private void containerExecutionSkipped(final TestIdentifier testIdentifier, final String reason) {
    if (!JUnitPlatformLauncherUtils.isSuite(testIdentifier)) {
      return;
    }

    Class<?> testClass = JUnitPlatformLauncherUtils.getJavaClass(testIdentifier);
    String testSuiteName =
        testClass != null ? testClass.getName() : testIdentifier.getLegacyReportingName();

    String testEngineId = JUnitPlatformLauncherUtils.getTestEngineId(testIdentifier);
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

  private void testMethodExecutionSkipped(
      final TestIdentifier testIdentifier, final MethodSource methodSource, final String reason) {
    String testEngineId = JUnitPlatformLauncherUtils.getTestEngineId(testIdentifier);
    String testFramework = getTestFramework(testEngineId);
    String testFrameworkVersion = versionByTestEngineId.get(testEngineId);

    String testSuiteName = methodSource.getClassName();
    String displayName = testIdentifier.getDisplayName();
    String testName = JUnitPlatformUtils.getTestName(displayName, methodSource, testEngineId);

    String testParameters = JUnitPlatformUtils.getParameters(methodSource, displayName);
    List<String> tags =
        testIdentifier.getTags().stream().map(TestTag::getName).collect(Collectors.toList());

    Class<?> testClass = JUnitPlatformUtils.getTestClass(methodSource);
    Method testMethod = JUnitPlatformUtils.getTestMethod(methodSource, testEngineId);
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

  private void testResourceExecutionSkipped(
      TestIdentifier testIdentifier, ClasspathResourceSource testSource, String reason) {
    String testEngineId = JUnitPlatformLauncherUtils.getTestEngineId(testIdentifier);
    if (!JUnitPlatformUtils.Cucumber.ENGINE_ID.equals(testEngineId)) {
      return;
    }

    String classpathResourceName = testSource.getClasspathResourceName();
    Pair<String, String> names =
        JUnitPlatformLauncherUtils.Cucumber.getFeatureAndScenarioNames(
            testPlan, testIdentifier, classpathResourceName);
    String testSuiteName = names.getLeft();
    String testName = names.getRight();

    String testFramework = getTestFramework(testEngineId);
    String testFrameworkVersion = versionByTestEngineId.get(testEngineId);

    List<String> tags =
        testIdentifier.getTags().stream().map(TestTag::getName).collect(Collectors.toList());

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestIgnore(
        testSuiteName,
        testName,
        null,
        testFramework,
        testFrameworkVersion,
        null,
        tags,
        null,
        null,
        null,
        reason);
  }

  private static @Nullable String getTestFramework(String testEngineId) {
    return testEngineId != null && testEngineId.startsWith("junit") ? "junit5" : testEngineId;
  }
}
