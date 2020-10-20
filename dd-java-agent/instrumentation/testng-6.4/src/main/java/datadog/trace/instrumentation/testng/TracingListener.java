package datadog.trace.instrumentation.testng;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.testng.TestNGDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.TraceScope;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

public class TracingListener implements ITestListener {

  @Override
  public void onTestStart(final ITestResult result) {
    final AgentSpan span = startSpan("testng.test");
    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);

    DECORATE.afterStart(span);
    DECORATE.onTestStart(span, result);
  }

  @Override
  public void onTestSuccess(final ITestResult result) {
    final AgentSpan span = AgentTracer.activeSpan();
    if (span == null) {
      return;
    }

    final TraceScope scope = AgentTracer.activeScope();
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

    final TraceScope scope = AgentTracer.activeScope();
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

    final TraceScope scope = AgentTracer.activeScope();
    if (scope != null) {
      scope.close();
    }

    DECORATE.onTestIgnored(span, result);
    DECORATE.beforeFinish(span);
    span.finish(span.getStartTime());
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
