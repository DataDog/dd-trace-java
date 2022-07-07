package datadog.trace.instrumentation.junit4;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.junit4.JUnit4Decorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.ArrayList;
import java.util.List;
import junit.runner.Version;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

public class TracingListener extends RunListener {

  private final String version;

  public TracingListener() {
    this.version = Version.id();
  }

  @Override
  public void testStarted(final Description description) throws Exception {
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

    DECORATE.afterStart(span, version);
    DECORATE.onTestStart(span, description);
  }

  @Override
  public void testFinished(final Description description) throws Exception {
    if (DECORATE.skipTrace(description)) {
      return;
    }

    final AgentSpan span = AgentTracer.activeSpan();
    if (span == null) {
      return;
    }

    final AgentScope scope = AgentTracer.activeScope();
    if (scope != null) {
      scope.close();
    }

    DECORATE.onTestFinish(span);
    DECORATE.beforeFinish(span);
    span.finish();
  }

  @Override
  public void testFailure(final Failure failure) throws Exception {
    if (DECORATE.skipTrace(failure.getDescription())) {
      return;
    }

    final AgentSpan span = AgentTracer.activeSpan();
    if (span == null) {
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
    if (span == null) {
      return;
    }

    DECORATE.onTestAssumptionFailure(span, failure);
  }

  @Override
  public void testIgnored(final Description description) throws Exception {
    if (DECORATE.skipTrace(description)) {
      return;
    }

    final List<String> testNames = new ArrayList<>();
    if (description.getMethodName() != null && !"".equals(description.getMethodName())) {
      testNames.add(description.getMethodName());
    } else {
      // If @Ignore annotation is kept at class level, the instrumentation
      // reports every method annotated with @Test as skipped test.
      testNames.addAll(DECORATE.testNames(description.getTestClass(), Test.class));
    }

    final Ignore ignore = description.getAnnotation(Ignore.class);
    final String reason = ignore != null ? ignore.value() : null;

    for (final String testName : testNames) {
      final AgentSpan span = startSpan("junit.test");
      DECORATE.afterStart(span, version);
      DECORATE.onTestIgnored(span, description, testName, reason);
      DECORATE.beforeFinish(span);
      // We set a duration of 1 ns, because a span with duration==0 has a special treatment in the
      // tracer.
      span.finishWithDuration(1L);
    }
  }
}
