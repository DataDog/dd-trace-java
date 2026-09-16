package datadog.trace.agent.test.scopediag;

import static org.junit.jupiter.api.Assertions.assertFalse;

import datadog.context.ContextContinuation;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.config.inversion.ConfigHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ScopeDiagnosticsJUnitFixtureTest extends AbstractInstrumentationTest {

  static {
    ConfigHelper.get().setConfigInversionStrict(ConfigHelper.StrictnessPolicy.TEST);
  }

  @BeforeAll
  static void recordSuiteSetupLifecycle() {
    recordResolvedContinuation("suite.setup");
  }

  @AfterAll
  static void recordSuiteCleanupLifecycle() {
    recordResolvedContinuation("suite.cleanup");
  }

  @Test
  void runsWithSuiteFixtureDiagnostics() {}

  private static void recordResolvedContinuation(String operationName) {
    AgentSpan span = tracer.startSpan("test", operationName);
    ContextContinuation continuation = tracer.capture(span);
    assertFalse(
        ScopeDiagnostics.report().records().isEmpty(),
        "suite fixture continuation should be recorded");
    continuation.release();
    span.finish();
  }
}
