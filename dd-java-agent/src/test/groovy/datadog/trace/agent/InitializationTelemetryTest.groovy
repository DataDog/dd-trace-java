package datadog.trace.agent

import datadog.environment.JavaVirtualMachine
import datadog.test.SimpleAgentMock
import jvmbootstraptest.InitializationTelemetryCheck
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.Timeout

@Timeout(30)
@IgnoreIf(reason = "SecurityManager is permanently disabled as of JDK 24", value = {
  JavaVirtualMachine.isJavaVersionAtLeast(24)
})
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
    def result = InitializationTelemetryCheck.runTestJvm(null)

    then:
    result.exitCode == 0
    result.telemetryJson.contains('library_entrypoint.complete')
  }

  def "test initial telemetry forwarder trace muted"() {
    when:
    def agent = new SimpleAgentMock().start()
    def result = InitializationTelemetryCheck.runTestJvm(null, agent.port)

    then:
    result.exitCode == 0
    result.telemetryJson.contains('library_entrypoint.complete')

    // Check that we have only one span related to sub-process execution,
    // and it is not initial telemetry forwarder.
    agent.spans.size() == 1
    def span = agent.spans.get(0)
    span.name == 'command_execution'
    span.resource == 'echo'

    cleanup:
    agent.close()
  }

  def "incomplete agent start-up"() {
    // In this case, the SecurityManager blocks a custom permission that is checked by bytebuddy causing
    // agent initialization to fail.  However, we should catch the exception allowing the application
    // to run normally.
    when:
    def result = InitializationTelemetryCheck.runTestJvm(InitializationTelemetryCheck.BlockByteBuddy)

    then:
    result.exitCode == 0
    !result.telemetryJson.contains('library_entrypoint.complete')
    result.telemetryJson.contains('error_type:java.lang.IllegalStateException')
    assert false
  }

  def "block forwarder env var"() {
    // In this case, the SecurityManager blocks access to the forwarder environment variable,
    // so the tracer is unable to report initialization telemetry
    when:
    def result = InitializationTelemetryCheck.runTestJvm(InitializationTelemetryCheck.BlockForwarderEnvVar)

    then:
    result.exitCode == 0
    // forwarder env var unreadable, so no telemetry output
    result.telemetryJson == null
  }

  def "block forwarder execution"() {
    // In this case, the SecurityManager blocks access to process execution, so the tracer is
    // unable to invoke the forwarder executable
    when:
    def result = InitializationTelemetryCheck.runTestJvm(InitializationTelemetryCheck.BlockForwarderExecution)

    then:
    result.exitCode == 0
    // forwarder execution is blocked, so no telemetry output
    result.telemetryJson == null
  }
}
