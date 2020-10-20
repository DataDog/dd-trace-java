package datadog.trace.instrumentation.junit5;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.junit5.JUnit5Decorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.TraceScope;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

public class TracingListener implements TestExecutionListener {

  @Override
  public void executionStarted(final TestIdentifier testIdentifier) {
    if (!testIdentifier.isTest()) {
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

              DECORATE.afterStart(span);
              DECORATE.onTestStart(span, methodSource);
            });
  }

  @Override
  public void executionFinished(
      final TestIdentifier testIdentifier, final TestExecutionResult testExecutionResult) {
    if (!testIdentifier.isTest()) {
      return;
    }

    testIdentifier
        .getSource()
        .filter(testSource -> testSource instanceof MethodSource)
        .ifPresent(
            testSource -> {
              final AgentSpan span = AgentTracer.activeSpan();
              if (span == null) {
                return;
              }

              final TraceScope scope = AgentTracer.activeScope();
              if (scope != null) {
                scope.close();
              }

              DECORATE.onTestFinish(span, testExecutionResult);
              DECORATE.beforeFinish(span);
              span.finish();
            });
  }

  @Override
  public void executionSkipped(final TestIdentifier testIdentifier, final String reason) {
    testIdentifier
        .getSource()
        .ifPresent(
            testSource -> {
              if (testSource instanceof ClassSource) {
                // The annotation @Disabled is kept at type level.
                executionSkipped((ClassSource) testSource, reason);
              } else if (testSource instanceof MethodSource) {
                // The annotation @Disabled is kept at method level.
                executionSkipped((MethodSource) testSource, reason);
              }
            });
  }

  private void executionSkipped(final ClassSource classSource, final String reason) {
    // If @Disabled annotation is kept at type level, the instrumentation
    // reports every method annotated with @Test as skipped test.
    final String testSuite = classSource.getClassName();
    final List<String> testNames =
        new ArrayList<>(DECORATE.testNames(classSource.getJavaClass(), Test.class));

    for (final String testName : testNames) {
      final AgentSpan span = startSpan("junit.test");
      DECORATE.afterStart(span);
      DECORATE.onTestIgnore(span, testSuite, testName, reason);
      DECORATE.beforeFinish(span);
      span.finish(span.getStartTime());
    }
  }

  private void executionSkipped(final MethodSource methodSource, final String reason) {
    final String testSuite = methodSource.getClassName();
    final String testName = methodSource.getMethodName();

    final AgentSpan span = startSpan("junit.test");
    DECORATE.afterStart(span);
    DECORATE.onTestIgnore(span, testSuite, testName, reason);
    DECORATE.beforeFinish(span);
    span.finish(span.getStartTime());
  }
}
