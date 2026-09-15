import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_AGENT_PORT;
import static datadog.trace.api.config.TracerConfig.AGENT_HOST;
import static datadog.trace.api.config.TracerConfig.TRACE_AGENT_PORT;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;

import datadog.trace.test.util.DDJavaSpecification;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.MinimumDurationRunningStartupCheckStrategy;

abstract class AbstractTraceAgentTest extends DDJavaSpecification {

  private static GenericContainer<?> agentContainer;

  @BeforeAll
  static void setupSpec() {
    // CI will provide us with agent container running along side our build.
    // When building locally, however, we need to take matters into our own hands
    // and we use 'testcontainers' for this.
    if (!"true".equals(System.getenv("CI"))) {
      Map<String, String> env = new HashMap<>();
      env.put("DD_APM_ENABLED", "true");
      env.put("DD_BIND_HOST", "0.0.0.0");
      env.put("DD_API_KEY", "invalid_key_but_this_is_fine");
      env.put("DD_HOSTNAME", "doesnotexist");
      env.put("DD_LOGS_STDOUT", "yes");
      agentContainer =
          new GenericContainer<>("datadog/agent:7.40.1")
              .withEnv(env)
              .withExposedPorts(DEFAULT_TRACE_AGENT_PORT)
              .withStartupTimeout(Duration.ofSeconds(120))
              // Apparently we need to sleep for a bit so agent's response
              // `{"service:,env:":1}` in rate_by_service.
              // This is clearly a race-condition and maybe we should avoid verifying complete
              // response
              .withStartupCheckStrategy(
                  new MinimumDurationRunningStartupCheckStrategy(Duration.ofSeconds(10)));
      agentContainer.start();
    }
  }

  @BeforeEach
  void setup() {
    injectSysConfig(AGENT_HOST, getAgentContainerHost());
    injectSysConfig(TRACE_AGENT_PORT, getAgentContainerPort());
  }

  static String getAgentContainerHost() {
    if (agentContainer != null) {
      return agentContainer.getHost();
    }

    return System.getenv("CI_AGENT_HOST");
  }

  static String getAgentContainerPort() {
    if (agentContainer != null) {
      return String.valueOf(agentContainer.getMappedPort(DEFAULT_TRACE_AGENT_PORT));
    }

    return String.valueOf(DEFAULT_TRACE_AGENT_PORT);
  }

  @AfterAll
  static void cleanupSpec() {
    if (agentContainer != null) {
      agentContainer.stop();
    }
  }
}
