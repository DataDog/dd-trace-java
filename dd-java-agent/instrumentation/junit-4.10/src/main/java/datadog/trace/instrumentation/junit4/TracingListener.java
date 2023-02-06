package datadog.trace.instrumentation.junit4;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.junit4.JUnit4Decorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import junit.runner.Version;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runners.model.TestClass;

public class TracingListener extends RunListener {

  private final String version;

  public TracingListener() {
    version = Version.id();

    final AgentSpan span = startSpan("junit.test_module");
    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);

    DECORATE.onTestModuleStart(span, version);
  }

  @Override
  public void testRunFinished(Result result) {
    final AgentSpan span = AgentTracer.activeSpan();
    if (span == null || !DECORATE.isTestModuleSpan(AgentTracer.activeSpan())) {
      return;
    }

    final AgentScope scope = AgentTracer.activeScope();
    if (scope != null) {
      scope.close();
    }

    DECORATE.onTestModuleFinish(span);
    span.finish();
  }

  public void testSuiteStarted(final TestClass junitTestClass) {
    if (DECORATE.skipTrace(junitTestClass)) {
      return;
    }

    boolean containsTestCases = !junitTestClass.getAnnotatedMethods(Test.class).isEmpty();
    if (!containsTestCases) {
      // Not all test suites contain tests.
      // Given that test suites can be nested in other test suites,
      // we are only interested in the innermost ones where the actual test cases reside
      return;
    }

    final AgentSpan span = startSpan("junit.test_suite");
    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);

    DECORATE.onTestSuiteStart(span, version, junitTestClass);
  }

  public void testSuiteFinished(final TestClass junitTestClass) {
    if (DECORATE.skipTrace(junitTestClass)) {
      return;
    }

    boolean containsTestCases = !junitTestClass.getAnnotatedMethods(Test.class).isEmpty();
    if (!containsTestCases) {
      // Not all test suites contain tests.
      // Given that test suites can be nested in other test suites,
      // we are only interested in the innermost ones where the actual test cases reside
      return;
    }

    final AgentSpan span = AgentTracer.activeSpan();
    if (span == null || !DECORATE.isTestSuiteSpan(AgentTracer.activeSpan())) {
      return;
    }

    final AgentScope scope = AgentTracer.activeScope();
    if (scope != null) {
      scope.close();
    }

    DECORATE.onTestSuiteFinish(span, junitTestClass);
    span.finish();
  }

  @Override
  public void testStarted(final Description description) {
    if (DECORATE.skipTrace(description)) {
      return;
    }

    // If there is an active span that represents a test
    // we don't want to generate another child test span.
    // This can happen when the user executes a certain test
    // using the different test engines.
    // (e.g. JUnit 4 tests using JUnit5 engine)
    if (DECORATE.isTestSpan(AgentTracer.activeSpan())) {
      return;
    }

    final AgentSpan span = startSpan("junit.test");
    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);

    DECORATE.onTestStart(span, version, description);
  }

  @Override
  public void testFinished(final Description description) {
    if (DECORATE.skipTrace(description)) {
      return;
    }

    final AgentSpan span = AgentTracer.activeSpan();
    if (!DECORATE.isTestSpan(span)) {
      return;
    }

    final AgentScope scope = AgentTracer.activeScope();
    if (scope != null) {
      scope.close();
    }

    DECORATE.onTestFinish(span, description);
    span.finish();
  }

  // the same callback is executed both for test cases and test suited (in case of setup/teardown
  // errors)
  @Override
  public void testFailure(final Failure failure) {
    if (DECORATE.skipTrace(failure.getDescription())) {
      return;
    }

    final AgentSpan span = AgentTracer.activeSpan();
    if (!DECORATE.isTestSpan(span) || !DECORATE.isTestSuiteSpan(span)) {
      return;
    }

    DECORATE.onTestFailure(span, failure);
  }

  @Override
  public void testAssumptionFailure(final Failure failure) {
    if (DECORATE.skipTrace(failure.getDescription())) {
      return;
    }

    final AgentSpan span = AgentTracer.activeSpan();
    if (!DECORATE.isTestSpan(span) || !DECORATE.isTestSuiteSpan(span)) {
      return;
    }

    String reason;
    Throwable throwable = failure.getException();
    if (throwable != null) {
      reason = throwable.getMessage();
    } else {
      reason = null;
    }

    Description description = failure.getDescription();
    if (JUnit4Utils.isTestCaseDescription(description)) {
      DECORATE.onTestAssumptionFailure(span, reason);
    } else if (JUnit4Utils.isTestSuiteDescription(description)) {
      DECORATE.onTestSuiteAssumptionFailure(span, version, description, reason);
    }
  }

  @Override
  public void testIgnored(final Description description) {
    if (DECORATE.skipTrace(description)) {
      return;
    }

    final AgentSpan span = startSpan("junit.test");

    final Ignore ignore = description.getAnnotation(Ignore.class);
    final String reason = ignore != null ? ignore.value() : null;

    if (JUnit4Utils.isTestCaseDescription(description)) {
      DECORATE.onTestIgnored(
          span, version, description, JUnit4Utils.getTestMethod(description), reason);
    } else if (JUnit4Utils.isTestSuiteDescription(description)) {
      DECORATE.onTestSuiteIgnored(span, version, description, reason);
    } else {
      return;
    }

    // We set a duration of 1 ns, because a span with duration==0 has a special treatment in the
    // tracer.
    span.finishWithDuration(1L);
  }
}
