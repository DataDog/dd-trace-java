package datadog.trace.agent

import datadog.trace.agent.test.IntegrationTestUtils
import jvmbootstraptest.AgentLoadedChecker
import spock.lang.Specification
import spock.lang.Timeout

@Timeout(30)
class TraceDisabledFeatureFlaggingShutdownTest extends Specification {

  def "feature flagging is stopped by the real agent when tracing is disabled"() {
    setup:
    def output = new ByteArrayOutputStream()
    def printStream = new PrintStream(output, true, "UTF-8")

    when:
    def exitCode = IntegrationTestUtils.runOnSeparateJvm(AgentLoadedChecker.getName()
      , [
        "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=debug",
        "-Ddd.trace.enabled=false",
        "-Ddd.feature.flags.enabled=true",
        "-Ddd.feature.flags.configuration.source=agentless",
        "-Ddd.jmxfetch.enabled=false",
        "-Ddd.profiling.enabled=false",
        "-Ddd.remote_config.enabled=false",
        "-Ddd.telemetry.enabled=false"
      ]
      , []
      , [:]
      , printStream)
    def logs = output.toString("UTF-8")

    then:
    exitCode == 0
    logs.contains("Shutting down agent")
    logs.contains("Feature Flagging system stopped")

    cleanup:
    printStream.close()
  }
}
