package datadog.trace.agent

import spock.lang.Specification
import spock.lang.Timeout

import jvmbootstraptest.InitializationTelemetryCheck

@Timeout(30)
class InitializationTelemetryTest extends Specification {
  def "incomplete agent start-up"() {
    // In this case, the SecurityManager blocks a custom permission that is checked by bytebuddy causing
    // agent initialization to fail.  However, we should catch the exception allowing the application
    // to run normally.
    when:
    def result = InitializationTelemetryCheck.runTestJvm(InitializationTelemetryCheck.BlockByteBuddy, true, "sleep,incomplete")

    then:
    result.exitCode == 0
    !result.telemetryJson.contains('library_entrypoint.complete')
    result.telemetryJson.contains('error_type:java.lang.IllegalStateException')
  }
}
