package datadog.smoketest.springboot;

import static datadog.smoketest.springboot.OpenFeatureFixtures.APPLICATION_JAR;
import static datadog.smoketest.springboot.OpenFeatureFixtures.FLAG_CONFIGURATION_PATH;
import static datadog.smoketest.springboot.OpenFeatureFixtures.agent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.smoketest.SmokeServerApp;
import datadog.smoketest.backend.MockAgentBackend;
import datadog.smoketest.backend.RemoteConfig;
import datadog.trace.agent.test.utils.OkHttpUtils;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A malformed flag configuration that Remote Configuration delivers before the SDK subscribes is
 * acknowledged by the agent, then reported as not applied once the SDK rejects it.
 */
class OpenFeatureMalformedRemoteConfigSmokeTest {
  private static final String MALFORMED_CONFIG = "{\"flags\":[]}";
  private static final int APPLY_STATE_ACKNOWLEDGED = 2;
  private static final int APPLY_STATE_ERROR = 3;
  private static final MockAgentBackend AGENT = agent();

  @RegisterExtension
  static final SmokeServerApp APP =
      SmokeServerApp.named("openfeature-malformed-remote-config")
          .jar(APPLICATION_JAR)
          .placeholder("agent.port", () -> Integer.toString(AGENT.port()))
          .jvmArgs(
              "-Ddd.remote_config.enabled=true",
              "-Ddd.remote_config.url=http://localhost:${agent.port}/v0.7/config")
          .args("--server.port=${app.httpPort}", "--openfeature.lazy-provider=true")
          .env("DD_FEATURE_FLAGS_CONFIGURATION_SOURCE", "remote_config")
          .backend(AGENT)
          // The provider never gets a valid configuration, so the OpenFeature SDK logs its failed
          // initialization.
          .allowedErrorLogs(" d.o.sdk.", " dev.openfeature.sdk.")
          .build();

  @Test
  void reportsAMalformedConfigurationReceivedBeforeTheSdkAsNotApplied() throws IOException {
    RemoteConfig remoteConfig = AGENT.remoteConfig();
    remoteConfig.setConfig(FLAG_CONFIGURATION_PATH, MALFORMED_CONFIG);

    // The agent receives the configuration while no SDK has subscribed.
    remoteConfig.waitForRequest(request -> applyState(request) == APPLY_STATE_ACKNOWLEDGED);
    Request registration =
        new Request.Builder()
            .url(APP.url() + "/openfeature/provider")
            .post(RequestBody.create(null, new byte[0]))
            .build();
    try (Response response = OkHttpUtils.client().newCall(registration).execute()) {
      assertEquals(202, response.code(), "provider registration");
    }

    // The SDK rejects it and the agent reports it as not applied.
    Map<String, Object> rejected =
        remoteConfig.waitForRequest(request -> applyState(request) == APPLY_STATE_ERROR);
    Object applyError = flagConfigurationState(rejected).get("apply_error");
    assertTrue(
        String.valueOf(applyError).contains("Malformed flag configuration"),
        "apply error: " + applyError);
  }

  private static int applyState(Map<String, Object> request) {
    Map<String, Object> state = flagConfigurationState(request);
    Object applyState = state == null ? null : state.get("apply_state");
    return applyState instanceof Number ? ((Number) applyState).intValue() : -1;
  }

  private static Map<String, Object> flagConfigurationState(Map<String, Object> request) {
    Object client = request.get("client");
    Object state = client instanceof Map ? ((Map<?, ?>) client).get("state") : null;
    Object configStates = state instanceof Map ? ((Map<?, ?>) state).get("config_states") : null;
    if (configStates instanceof List) {
      for (Object configState : (List<?>) configStates) {
        if (configState instanceof Map
            && "FFE_FLAGS".equals(((Map<?, ?>) configState).get("product"))) {
          return Json.object(configState);
        }
      }
    }
    return null;
  }
}
