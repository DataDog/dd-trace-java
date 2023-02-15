package datadog.trace.instrumentation.junit5;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.TestDecorator.TEST_SKIP;
import static datadog.trace.instrumentation.junit5.JUnit5Decorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

public class TracingListener implements TestExecutionListener {

  private final Map<String, String> versionsByEngineId;

  private volatile TestPlan testPlan;

  public TracingListener(Iterable<TestEngine> testEngines) {
    final Map<String, String> versions = new HashMap<>();
    testEngines.forEach(
        testEngine ->
            testEngine
                .getVersion()
                .ifPresent(version -> versions.put(testEngine.getId(), version)));
    versionsByEngineId = Collections.unmodifiableMap(versions);
  }

  @Override
  public void testPlanExecutionStarted(TestPlan testPlan) {
    this.testPlan = testPlan;

    final AgentSpan span = startSpan("junit.test_module");
    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);

    String versions = getVersions(testPlan);
    DECORATE.onTestModuleStart(span, versions);
  }

  @Override
  public void testPlanExecutionFinished(TestPlan testPlan) {
    final AgentSpan span = AgentTracer.activeSpan();
    if (!DECORATE.isTestModuleSpan(AgentTracer.activeSpan())) {
      return;
    }

    final AgentScope scope = AgentTracer.activeScope();
    if (scope != null) {
      scope.close();
    }

    DECORATE.onTestModuleFinish(span);
    span.finish();
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

  private void containerExecutionStarted(TestIdentifier testIdentifier) {
    if (JUnit5Utils.isRootContainer(testIdentifier) || JUnit5Utils.isTestCase(testIdentifier)) {
      return;
    }

    if (!DECORATE.tryTestSuiteStart(testIdentifier)) {
      return;
    }

    final AgentSpan span = startSpan("junit.test_suite");
    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);

    DECORATE.onTestSuiteStart(span, getVersion(testIdentifier), testIdentifier);
  }

  private void containerExecutionFinished(
      TestIdentifier testIdentifier, final TestExecutionResult testExecutionResult) {
    if (JUnit5Utils.isRootContainer(testIdentifier) || JUnit5Utils.isTestCase(testIdentifier)) {
      return;
    }

    if (!DECORATE.tryTestSuiteFinish(testIdentifier)) {
      return;
    }

    final AgentSpan span = AgentTracer.activeSpan();
    if (!DECORATE.isTestSuiteSpan(AgentTracer.activeSpan())) {
      return;
    }

    if (JUnit5Utils.isAssumptionFailure(testExecutionResult)) {
      for (TestIdentifier child : testPlan.getChildren(testIdentifier)) {
        executionSkipped(
            child, testExecutionResult.getThrowable().map(Throwable::getMessage).orElse(null));
      }
    }

    final AgentScope scope = AgentTracer.activeScope();
    if (scope != null) {
      scope.close();
    }

    DECORATE.onPossibleFailure(span, testExecutionResult);
    DECORATE.onTestSuiteFinish(span, testIdentifier);
    span.finish();
  }

  private void testCaseExecutionStarted(TestIdentifier testIdentifier) {
    // If there is an active span that represents a test
    // we don't want to generate another child test span.
    // This can happen when the user executes a certain test
    // using the different test engines.
    // (e.g. JUnit 4 tests using JUnit5 engine)
    if (DECORATE.isTestSpan(AgentTracer.activeSpan())) {
      return;
    }

    testIdentifier
        .getSource()
        .filter(testSource -> testSource instanceof MethodSource)
        .map(testSource -> (MethodSource) testSource)
        .ifPresent(
            methodSource -> {
              final AgentSpan span = startSpan("junit.test");
              final AgentScope scope = activateSpan(span);
              scope.setAsyncPropagation(true);

              final String version = getVersion(testIdentifier);

              DECORATE.onTestStart(span, version, methodSource, testIdentifier);
            });
  }

  private void testCaseExecutionFinished(
      TestIdentifier testIdentifier, final TestExecutionResult testExecutionResult) {
    testIdentifier
        .getSource()
        .filter(testSource -> testSource instanceof MethodSource)
        .ifPresent(
            testSource -> {
              final AgentSpan span = AgentTracer.activeSpan();
              if (span == null || !DECORATE.isTestSpan(AgentTracer.activeSpan())) {
                return;
              }

              final AgentScope scope = AgentTracer.activeScope();
              if (scope != null) {
                scope.close();
              }

              DECORATE.onPossibleFailure(span, testExecutionResult);
              DECORATE.onTestFinish(span, (MethodSource) testSource);
              span.finish();
            });
  }

  @Override
  public void executionSkipped(final TestIdentifier testIdentifier, final String reason) {
    testIdentifier
        .getSource()
        .ifPresent(
            testSource -> {
              final String version = getVersion(testIdentifier);
              if (testSource instanceof ClassSource) {
                // The annotation @Disabled is kept at type level.
                containerExecutionSkipped(testIdentifier, version, reason);
              } else if (testSource instanceof MethodSource) {
                // The annotation @Disabled is kept at method level.
                testCaseExecutionSkipped(
                    testIdentifier, (MethodSource) testSource, version, reason);
              }
            });
  }

  private void containerExecutionSkipped(
      final TestIdentifier testIdentifier, final String version, final String reason) {
    final AgentSpan span = startSpan("junit.test_suite");
    final AgentScope scope = activateSpan(span);

    DECORATE.onTestSuiteStart(span, version, testIdentifier);
    span.setTag(Tags.TEST_SKIP_REASON, reason);

    for (TestIdentifier child : testPlan.getChildren(testIdentifier)) {
      executionSkipped(child, reason);
    }

    DECORATE.onTestSuiteFinish(span, testIdentifier);

    scope.close();
    span.finish();
  }

  private void testCaseExecutionSkipped(
      final TestIdentifier testIdentifier,
      final MethodSource methodSource,
      final String version,
      final String reason) {
    final AgentSpan span = startSpan("junit.test");
    final AgentScope scope = activateSpan(span);

    DECORATE.onTestStart(span, version, methodSource, testIdentifier);

    span.setTag(Tags.TEST_STATUS, TEST_SKIP);
    span.setTag(Tags.TEST_SKIP_REASON, reason);

    DECORATE.onTestFinish(span, methodSource);

    scope.close();
    // We set a duration of 1 ns, because a span with duration==0 has a special treatment in the
    // tracer.
    span.finishWithDuration(1L);
  }

  private String getVersions(TestPlan testPlan) {
    StringBuilder versions = new StringBuilder();
    for (TestIdentifier root : testPlan.getRoots()) {
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

  private String getVersion(TestIdentifier testIdentifier) {
    return UniqueId.parse(testIdentifier.getUniqueId())
        .getEngineId()
        .map(versionsByEngineId::get)
        .orElse(null);
  }
}
