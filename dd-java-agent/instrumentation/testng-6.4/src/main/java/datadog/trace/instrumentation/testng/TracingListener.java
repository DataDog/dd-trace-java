package datadog.trace.instrumentation.testng;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.testng.TestNGDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

public class TracingListener implements ITestListener {

  private final String version;

  public TracingListener(final String version) {
    this.version = version;
  }

  @Override
  public void onTestStart(final ITestResult result) {
    // If there is an active span that represents a test
    // we don't want to generate another child test span.
    // This can happen when the user executes a certain test
    // using the different test engines.
    // (e.g. JUnit 4 tests using JUnit5 engine)
    if (DECORATE.isTestSpan(AgentTracer.activeSpan())) {
      return;
    }

    final AgentSpan span = startSpan("testng.test");
    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);

    DECORATE.afterStart(span, version);
    DECORATE.onTestStart(span, result);
  }

  @Override
  public void onTestSuccess(final ITestResult result) {
    final AgentSpan span = AgentTracer.activeSpan();
    if (span == null) {
      return;
    }

    final AgentScope scope = AgentTracer.activeScope();
    if (scope != null) {
      scope.close();
    }

    DECORATE.onTestSuccess(span);
    DECORATE.beforeFinish(span);
    span.finish();
  }

  @Override
  public void onTestFailure(final ITestResult result) {
    final AgentSpan span = AgentTracer.activeSpan();
    if (span == null) {
      return;
    }

    final AgentScope scope = AgentTracer.activeScope();
    if (scope != null) {
      scope.close();
    }

    DECORATE.onTestFailure(span, result);
    DECORATE.beforeFinish(span);
    span.finish();
  }

  @Override
  public void onTestSkipped(final ITestResult result) {
    final AgentSpan span = AgentTracer.activeSpan();
    if (span == null) {
      return;
    }

    final AgentScope scope = AgentTracer.activeScope();
    if (scope != null) {
      scope.close();
    }

    DECORATE.onTestIgnored(span, result);
    DECORATE.beforeFinish(span);
    // We set a duration of 1 ns, because a span with duration==0 has a special treatment in the
    // tracer.
    span.finishWithDuration(1L);
  }

  @Override
  public void onTestFailedButWithinSuccessPercentage(final ITestResult result) {
    onTestFailure(result);
  }

  @Override
  public void onStart(final ITestContext context) {}

  @Override
  public void onFinish(final ITestContext context) {}
}
