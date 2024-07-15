package datadog.trace.agent

import spock.lang.Specification
import spock.lang.Timeout

import jvmbootstraptest.InitializationTelemetryCheck

@Timeout(30)
class InitializationTelemetryTest extends Specification {
//  def "block agent start-up"() {
//    setup:
//    System.out.println("block agent start-up")
//    
//    expect:
//    InitializationTelemetryCheck.runTestJvm(InitializationTelemetryCheck.BlockAgentLoading) != 0
//  }
  
  def "incomplete agent start-up"() {
    setup:
    System.out.println("incomplete agent start-up")
    
    expect:
    InitializationTelemetryCheck.runTestJvm(InitializationTelemetryCheck.BlockByteBuddy, true) == 1
  }
  
// def "block forwarder env var"() {
//    setup:
//    System.out.println("block forwarder env var")
//    
//    expect:
//    InitializationTelemetryCheck.runTestJvm(InitializationTelemetryCheck.BlockForwarderEnvVar) == 0
// }
}