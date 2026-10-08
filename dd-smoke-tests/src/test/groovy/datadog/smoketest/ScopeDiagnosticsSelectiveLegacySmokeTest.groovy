package datadog.smoketest

import static datadog.trace.agent.test.scopediag.ScopeDiagnosticsCheck.LEAKED

import datadog.trace.agent.test.scopediag.ScopeDiagnosticsCheck

/** Verifies selective enforcement through the legacy launcher. */
class ScopeDiagnosticsSelectiveLegacySmokeTest extends ScopeDiagnosticsLegacySmokeTest {
  @Override
  protected ScopeDiagnosticsCheck[] disabledScopeContinuationChecks() {
    [LEAKED] as ScopeDiagnosticsCheck[]
  }

  @Override
  protected String disabledScopeContinuationChecksReason() {
    'Known fixture leak'
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    ProcessBuilder builder = super.createProcessBuilder()
    builder.command().set(builder.command().size() - 1, 'leak')
    builder
  }
}
