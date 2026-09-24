package datadog.smoketest

import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/** Exercises companion injection through the legacy smoke-test launch properties. */
class ScopeDiagnosticsLegacySmokeTest extends AbstractSmokeTest {
  @Override
  ProcessBuilder createProcessBuilder() {
    String classes = Paths.get(ScopeDiagnosticsTestApp.protectionDomain.codeSource.location.toURI()).toString()
    new ProcessBuilder([
      javaPath(),
      *defaultJavaProperties,
      '-Ddd.profiling.enabled=false',
      '-Ddd.telemetry.enabled=false',
      '-Ddd.remote_config.enabled=false',
      '-cp',
      classes,
      ScopeDiagnosticsTestApp.name,
      'resolved'
    ] as String[])
  }

  def 'completed CLI is checked even when it exits before the feature'() {
    expect:
    testedProcess.waitFor(30, TimeUnit.SECONDS)
    testedProcess.exitValue() == 0
  }
}
