package datadog.trace.agent

import spock.lang.Specification
import spock.lang.Timeout

import jvmbootstraptest.InitializationTelemetryCheck

@Timeout(30)
class InitializationTelemetryTest extends Specification {
  def "block agent start-up"() {
    // In this case, the SecurityManager blocks loading of the Premain Class,
    // so the JVM is expected to terminate with an error
    when:
    def result = InitializationTelemetryCheck.runTestJvm(InitializationTelemetryCheck.BlockAgentLoading)

    then:
    result.exitCode != 0
    // JVM fails before premain, so no telemetry output
    result.telemetryJson == null
  }

  def "normal start-up"() {
    when:
    def result = InitializationTelemetryCheck.runTestJvm(null, true, "sleep,normal")

    then:
    result.exitCode == 0
    result.telemetryJson.contains('library_entrypoint.complete')
  }

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

  def "block forwarder env var"() {
    // In this case, the SecurityManager blocks access to the forwarder environment variable,
    // so the tracer is unable to report initialization telemetry
    when:
    def result = InitializationTelemetryCheck.runTestJvm(InitializationTelemetryCheck.BlockForwarderEnvVar, true)

    then:
    result.exitCode == 0
    // forwarder env var unreadable, so no telemetry output
    result.telemetryJson == null
  }

  def "block forwarder execution"() {
    // In this case, the SecurityManager blocks access to process execution, so the tracer is
    // unable to invoke the forwarder executable
    when:
    def result = InitializationTelemetryCheck.runTestJvm(InitializationTelemetryCheck.BlockForwarderExecution, true)

    then:
    result.exitCode == 0
    // forwarder execution is blocked, so no telemetry output
    result.telemetryJson == null
  }
}
