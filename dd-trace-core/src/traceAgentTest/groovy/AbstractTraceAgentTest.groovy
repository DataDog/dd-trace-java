import datadog.trace.api.ConfigDefaults
import datadog.trace.api.config.TracerConfig
import datadog.trace.test.util.DDSpecification
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.startupcheck.MinimumDurationRunningStartupCheckStrategy
import spock.lang.Shared

import java.time.Duration

abstract class AbstractTraceAgentTest extends DDSpecification {
  @Shared
  def agentContainer

  def setupSpec() {
    /*
     CI will provide us with agent container running along side our build.
     When building locally, however, we need to take matters into our own hands
     and we use 'testcontainers' for this.
     */
    if ("true" != System.getenv("CI")) {
      agentContainer = new GenericContainer("datadog/agent:7.34.0")
        .withEnv(["DD_APM_ENABLED": "true",
          "DD_BIND_HOST"  : "0.0.0.0",
          "DD_API_KEY"    : "invalid_key_but_this_is_fine",
          "DD_LOGS_STDOUT": "yes"])
        .withExposedPorts(datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_AGENT_PORT)
        .withStartupTimeout(Duration.ofSeconds(120))
        // Apparently we need to sleep for a bit so agent's response `{"service:,env:":1}` in rate_by_service.
        // This is clearly a race-condition and maybe we should avoid verifying complete response
        .withStartupCheckStrategy(new MinimumDurationRunningStartupCheckStrategy(Duration.ofSeconds(10)))
      agentContainer.start()
    }
  }

  def setup() {
    injectSysConfig(TracerConfig.AGENT_HOST, getAgentContainerHost())
    injectSysConfig(TracerConfig.TRACE_AGENT_PORT, getAgentContainerPort())
  }

  String getAgentContainerHost() {
    if (agentContainer) {
      return (String) agentContainer.getHost()
    }

    return System.getenv("CI_AGENT_HOST")
  }

  String getAgentContainerPort() {
    if (agentContainer) {
      return (String) agentContainer.getMappedPort(ConfigDefaults.DEFAULT_TRACE_AGENT_PORT)
    }

    return ConfigDefaults.DEFAULT_TRACE_AGENT_PORT
  }

  def cleanupSpec() {
    agentContainer?.stop()
  }
}
