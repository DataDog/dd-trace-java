package datadog.trace.instrumentation.junit4;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.junit4.JUnit4Decorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import junit.runner.Version;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runners.model.TestClass;

public class TracingListener extends RunListener {

  private final String version;

  public TracingListener() {
    version = Version.id();
  }

  public void testSuiteStarted(final TestClass testClass) {
    // TODO implement test-suite level visibility
  }

  public void testSuiteFinished(final TestClass testClass) {
    // TODO implement test-suite level visibility
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
    if (span == null || !DECORATE.isTestSpan(AgentTracer.activeSpan())) {
      return;
    }

    final AgentScope scope = AgentTracer.activeScope();
    if (scope != null) {
      scope.close();
    }

    DECORATE.onTestFinish(span);
    span.finish();
  }

  @Override
  public void testFailure(final Failure failure) {
    if (DECORATE.skipTrace(failure.getDescription())) {
      return;
    }

    final AgentSpan span = AgentTracer.activeSpan();
    if (span == null || !DECORATE.isTestSpan(AgentTracer.activeSpan())) {
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
    if (span == null || !DECORATE.isTestSpan(AgentTracer.activeSpan())) {
      return;
    }

    DECORATE.onTestAssumptionFailure(span, failure);
  }

  @Override
  public void testIgnored(final Description description) {
    if (DECORATE.skipTrace(description)) {
      return;
    }

    final List<Method> testMethods;
    if (description.getMethodName() != null && !"".equals(description.getMethodName())) {
      testMethods = Collections.singletonList(JUnit4Utils.getTestMethod(description));
    } else if (description.getTestClass() != null) {
      // If @Ignore annotation is kept at class level, the instrumentation
      // reports every method annotated with @Test as skipped test.
      testMethods = DECORATE.testMethods(description.getTestClass(), Test.class);
    } else {
      testMethods = Collections.emptyList();
    }

    final Ignore ignore = description.getAnnotation(Ignore.class);
    final String reason = ignore != null ? ignore.value() : null;

    for (final Method testMethod : testMethods) {
      final AgentSpan span = startSpan("junit.test");
      DECORATE.onTestIgnored(span, version, description, testMethod, reason);
      // We set a duration of 1 ns, because a span with duration==0 has a special treatment in the
      // tracer.
      span.finishWithDuration(1L);
    }
  }
}
