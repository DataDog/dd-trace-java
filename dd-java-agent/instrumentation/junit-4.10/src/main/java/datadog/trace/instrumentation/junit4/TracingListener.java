package datadog.trace.instrumentation.junit4;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.junit4.JUnit4Decorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.TraceScope;
import java.util.ArrayList;
import java.util.List;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

public class TracingListener extends RunListener {

  @Override
  public void testStarted(final Description description) throws Exception {
    if (DECORATE.skipTrace(description)) {
      return;
    }

    final AgentSpan span = startSpan("junit.test");
    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);

    DECORATE.afterStart(span);
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

    final TraceScope scope = AgentTracer.activeScope();
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
      DECORATE.afterStart(span);
      DECORATE.onTestIgnored(span, description, testName, reason);
      DECORATE.beforeFinish(span);
      span.finish(span.getStartTime());
    }
  }
}
