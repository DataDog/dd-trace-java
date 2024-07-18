package datadog.trace.agent

import spock.lang.Specification
import spock.lang.Timeout

import jvmbootstraptest.InitializationTelemetryCheck

@Timeout(30)
class InitializationTelemetryTest extends Specification {
  def "block agent start-up"() {
    // In this case, the SecurityManager blocks loading of the Premain Class, 
    // so the JVM is expected to terminate with an error
    expect:
    InitializationTelemetryCheck.runTestJvm(InitializationTelemetryCheck.BlockAgentLoading) != 0
  }

  def "normal start-up"() {
    expect:
    InitializationTelemetryCheck.runTestJvm(null) == 0
  }

  def "incomplete agent start-up"() {
    // In this case, the SecurityManager blocks a custom permission that is checked by bytebuddy causing 
    // agent initialization to fail.  However, we should catch the exception allowing the application 
    // to run normally.
    expect:
    InitializationTelemetryCheck.runTestJvm(InitializationTelemetryCheck.BlockByteBuddy) == 0
  }

  def "block forwarder env var"() {
    // In this case, the SecurityManager blocks access to the forwarder environment variable, 
    // so the tracer is unable to report initialization telemetry
    expect:
    InitializationTelemetryCheck.runTestJvm(InitializationTelemetryCheck.BlockForwarderEnvVar, true) == 0
  } 

  def "block forwarder execution"() {
    // In this case, the SecurityManager blocks access to process execution, so the tracer is 
    // unable to invoke the forwarder executable
    expect:
    InitializationTelemetryCheck.runTestJvm(InitializationTelemetryCheck.BlockForwarderExecution, true) == 0
  }
}
