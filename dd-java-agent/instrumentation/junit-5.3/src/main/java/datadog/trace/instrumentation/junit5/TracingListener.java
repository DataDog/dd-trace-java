package datadog.trace.instrumentation.junit5;

import static datadog.trace.instrumentation.junit5.JUnit5Decorator.DECORATE;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

public class TracingListener implements TestExecutionListener {

  private final TestEventsHandler testEventsHandler;

  private final Map<String, String> versionsByEngineId;

  private volatile TestPlan testPlan;

  public TracingListener(final Iterable<TestEngine> testEngines) {
    final Map<String, String> versions = new HashMap<>();
    for (TestEngine testEngine : testEngines) {
      testEngine.getVersion().ifPresent(version -> versions.put(testEngine.getId(), version));
    }
    versionsByEngineId = Collections.unmodifiableMap(versions);
    testEventsHandler = InstrumentationBridge.getTestEventsHandler(DECORATE);
  }

  @Override
  public void testPlanExecutionStarted(final TestPlan testPlan) {
    this.testPlan = testPlan;

    String versions = getVersions(testPlan);
    testEventsHandler.onTestModuleStart(versions);
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
    String version = getVersion(testIdentifier);
    List<String> tags =
        testIdentifier.getTags().stream().map(TestTag::getName).collect(Collectors.toList());
    testEventsHandler.onTestSuiteStart(testSuiteName, testClass, version, tags);
  }

  private void containerExecutionFinished(
      final TestIdentifier testIdentifier, final TestExecutionResult testExecutionResult) {
    if (JUnit5Utils.isRootContainer(testIdentifier) || JUnit5Utils.isTestCase(testIdentifier)) {
      return;
    }

    Throwable throwable = testExecutionResult.getThrowable().orElse(null);
    if (throwable != null) {
      if (JUnit5Utils.isAssumptionFailure(throwable)) {

        String reason = throwable.getMessage();
        testEventsHandler.onSkip(reason);

        for (TestIdentifier child : testPlan.getChildren(testIdentifier)) {
          executionSkipped(child, reason);
        }

      } else {
        testEventsHandler.onFailure(throwable);
      }
    }

    Class<?> testClass = JUnit5Utils.getJavaClass(testIdentifier);
    String testSuiteName =
        testClass != null ? testClass.getName() : testIdentifier.getLegacyReportingName();
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

    String version = getVersion(testIdentifier);

    Class<?> testClass = JUnit5Utils.getTestClass(methodSource);
    Method testMethod = JUnit5Utils.getTestMethod(methodSource);

    testEventsHandler.onTestStart(
        testSuitName, testName, testParameters, tags, version, testClass, testMethod);
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
        //        testEventsHandler.onSkip(throwable.getMessage());
        testEventsHandler.onTestSkip(testSuiteName, testClass, testName, throwable.getMessage());
      } else {
        //        testEventsHandler.onFailure(throwable);
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
    String version = getVersion(testIdentifier);
    List<String> tags =
        testIdentifier.getTags().stream().map(TestTag::getName).collect(Collectors.toList());

    testEventsHandler.onTestSuiteStart(testSuiteName, testClass, version, tags);
    testEventsHandler.onSkip(reason);

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
    String version = getVersion(testIdentifier);

    Class<?> testClass = JUnit5Utils.getTestClass(methodSource);
    Method testMethod = JUnit5Utils.getTestMethod(methodSource);

    testEventsHandler.onTestIgnore(
        testSuiteName, testName, testParameters, tags, version, testClass, testMethod, reason);
  }

  private String getVersions(final TestPlan testPlan) {
    StringBuilder versions = new StringBuilder();
    for (TestIdentifier root : testPlan.getRoots()) {
      Set<TestIdentifier> rootChildren = testPlan.getChildren(root);
      if (rootChildren.isEmpty()) {
        continue;
      }
      String version = getVersion(root);
      if (version != null) {
        if (versions.length() > 0) {
          versions.append(',');
        }
        versions.append(version);
      }
    }
    return versions.length() > 0 ? versions.toString() : null;
  }

  private String getVersion(final TestIdentifier testIdentifier) {
    return UniqueId.parse(testIdentifier.getUniqueId())
        .getEngineId()
        .map(versionsByEngineId::get)
        .orElse(null);
  }
}
