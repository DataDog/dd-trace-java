package datadog.trace.instrumentation.junit5;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.junit5.JUnit5Decorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

public class TracingListener implements TestExecutionListener {

  private final Map<String, String> versionsByEngineId;

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
  public void executionStarted(final TestIdentifier testIdentifier) {
    if (!testIdentifier.isTest()) {
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

    testIdentifier
        .getSource()
        .filter(testSource -> testSource instanceof MethodSource)
        .map(testSource -> (MethodSource) testSource)
        .ifPresent(
            methodSource -> {
              final AgentSpan span = startSpan("junit.test");
              final AgentScope scope = activateSpan(span);
              scope.setAsyncPropagation(true);

              final String version =
                  UniqueId.parse(testIdentifier.getUniqueId())
                      .getEngineId()
                      .map(versionsByEngineId::get)
                      .orElse(null);
              DECORATE.afterStart(span, version);
              DECORATE.onTestStart(span, methodSource, testIdentifier);
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

              final AgentScope scope = AgentTracer.activeScope();
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
              final String version =
                  UniqueId.parse(testIdentifier.getUniqueId())
                      .getEngineId()
                      .map(versionsByEngineId::get)
                      .orElse(null);
              if (testSource instanceof ClassSource) {
                // The annotation @Disabled is kept at type level.
                executionSkipped((ClassSource) testSource, version, reason);
              } else if (testSource instanceof MethodSource) {
                // The annotation @Disabled is kept at method level.
                executionSkipped((MethodSource) testSource, version, reason);
              }
            });
  }

  private void executionSkipped(
      final ClassSource classSource, final String version, final String reason) {
    // If @Disabled annotation is kept at type level, the instrumentation
    // reports every method annotated with @Test as skipped test.
    final String testSuite = classSource.getClassName();
    final List<String> testNames =
        new ArrayList<>(DECORATE.testNames(classSource.getJavaClass(), Test.class));

    for (final String testName : testNames) {
      final AgentSpan span = startSpan("junit.test");
      DECORATE.afterStart(span, version);
      DECORATE.onTestIgnore(span, testSuite, testName, reason);
      DECORATE.beforeFinish(span);
      // We set a duration of 1 ns, because a span with duration==0 has a special treatment in the
      // tracer.
      span.finishWithDuration(1L);
    }
  }

  private void executionSkipped(
      final MethodSource methodSource, final String version, final String reason) {
    final String testSuite = methodSource.getClassName();
    final String testName = methodSource.getMethodName();

    final AgentSpan span = startSpan("junit.test");
    DECORATE.afterStart(span, version);
    DECORATE.onTestIgnore(span, testSuite, testName, reason);
    DECORATE.beforeFinish(span);
    // We set a duration of 1 ns, because a span with duration==0 has a special treatment in the
    // tracer.
    span.finishWithDuration(1L);
  }
}
