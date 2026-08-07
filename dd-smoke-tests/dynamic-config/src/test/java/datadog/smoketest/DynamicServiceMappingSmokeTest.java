package datadog.smoketest;

import static datadog.smoketest.dynamicconfig.ServiceMappingApplication.MAPPED_SERVICE_NAME;
import static datadog.smoketest.dynamicconfig.ServiceMappingApplication.ORIGINAL_SERVICE_NAME;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.smoketest.backend.AgentBackend;
import datadog.smoketest.backend.TestAgentBackend;
import datadog.smoketest.dynamicconfig.ServiceMappingApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies dynamic service mapping via Remote Configuration: pushes an {@code APM_TRACING}
 * service-mapping config to the test agent, and the launched {@link ServiceMappingApplication}
 * exits 0 once its tracer applies the mapping ({@value
 * ServiceMappingApplication#ORIGINAL_SERVICE_NAME} -&gt; {@value
 * ServiceMappingApplication#MAPPED_SERVICE_NAME}) from its {@code /v0.7/config} poll. Ported from
 * the Groovy {@code DynamicServiceMappingSmokeTest}.
 *
 * <p>The tracer's Remote Config poller shares the agent HTTP client that carries the {@code
 * X-Datadog-Test-Session-Token}, so the config pushed to this backend's session reaches this app's
 * tracer. Telemetry is not the subject here, so the default telemetry check is skipped.
 */
class DynamicServiceMappingSmokeTest {

  // Inline backend owned by the app; held as a field so the test can push a remote-config payload.
  private static final TestAgentBackend agent = AgentBackend.testAgentBuilder().build();

  @RegisterExtension
  static final SmokeCliApp app =
      SmokeCliApp.named("dynamic-service-mapping")
          .mainClass(ServiceMappingApplication.class.getName())
          .classpath(System.getProperty("datadog.smoketest.shadowJar.path"))
          .jvmArgs("-Ddd.remote_config.enabled=true", "-Ddd.remote_config.poll_interval.seconds=1")
          .backend(agent)
          .skipTelemetryCheck()
          .build();

  @Test
  void updatedServiceMappingObserved() {
    // Push a service-mapping override; the tracer picks it up on its next /v0.7/config poll and the
    // app exits 0 once it observes the remapped service name (or exits 1 after its 10s timeout).
    agent
        .remoteConfig()
        .setConfig(
            "datadog/2/APM_TRACING/config_overrides/config",
            "{\"lib_config\":{\"tracing_service_mapping\":[{"
                + "\"from_key\":\""
                + ORIGINAL_SERVICE_NAME
                + "\",\"to_name\":\""
                + MAPPED_SERVICE_NAME
                + "\"}]}}");
    app.assertCompletesWithValue(30, SECONDS, 0);
  }
}
