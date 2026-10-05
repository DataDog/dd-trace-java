package datadog.trace.agent.test.scopediag

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.config.inversion.ConfigHelper
import spock.lang.Requires
import spock.lang.Specification

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

  @TrackScopeContinuations(enabled = false, reason = "synthetic fixture testing method-level opt-out")
  def "runs with diagnostics disabled for this feature"() {
    expect:
    ScopeDiagnostics.recordingWindow() == null
  }

  def "restarts recording after aborted suite setup"() {
    given:
    scopeDiagnosticsSuiteSetupPending = true
    def span = TEST_TRACER.startSpan("test", "aborted.suite.setup")
    def continuation = TEST_TRACER.capture(span)

    when:
    onSuiteSetupFailure()

    then:
    def failure = thrown(AssertionError)
    failure.message.contains("Scope continuation problems detected")
    ScopeDiagnostics.report().records().isEmpty()

    cleanup:
    continuation.release()
    span.finish()
  }

  private void recordResolvedContinuation(String operationName) {
    def span = TEST_TRACER.startSpan("test", operationName)
    def continuation = TEST_TRACER.capture(span)
    assert !ScopeDiagnostics.report().records().isEmpty(): "suite fixture continuation should be recorded"
    continuation.release()
    span.finish()
  }
}

@TrackScopeContinuations
abstract class ScopeDiagnosticsSpockFixtureBase extends Specification implements ScopeDiagnosticsSpockSupport {
  @Override
  void onSuiteSetupFailure() {
    throw new AssertionError("scope continuation leaked")
  }
}

@Requires({
  System.getProperty("scope.diagnostics.run.failing.fixture") == "true"
})
class FailingScopeDiagnosticsSpockFixture extends ScopeDiagnosticsSpockFixtureBase {
  void setupSpec() {
    throw new IllegalStateException("suite setup failed")
  }

  def "is never run"() {
    expect:
    true
  }
}
