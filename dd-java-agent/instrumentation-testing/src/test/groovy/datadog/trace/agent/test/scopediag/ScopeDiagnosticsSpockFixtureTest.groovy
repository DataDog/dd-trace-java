package datadog.trace.agent.test.scopediag

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.config.inversion.ConfigHelper

class ScopeDiagnosticsSpockFixtureTest extends InstrumentationSpecification {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    ConfigHelper.get().setConfigInversionStrict(ConfigHelper.StrictnessPolicy.TEST)
  }

  void setupSpec() {
    recordResolvedContinuation("suite.setup")
  }

  void cleanupSpec() {
    recordResolvedContinuation("suite.cleanup")
  }

  def "runs with suite fixture diagnostics"() {
    expect:
    true
  }

  private void recordResolvedContinuation(String operationName) {
    def span = TEST_TRACER.startSpan("test", operationName)
    def continuation = TEST_TRACER.capture(span)
    assert !ScopeDiagnostics.report().records().isEmpty(): "suite fixture continuation should be recorded"
    continuation.release()
    span.finish()
  }
}
