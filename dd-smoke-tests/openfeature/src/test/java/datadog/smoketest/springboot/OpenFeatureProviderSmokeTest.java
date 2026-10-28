package datadog.smoketest.springboot;

import static datadog.remoteconfig.Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES;
import static datadog.smoketest.backend.RemoteConfig.capabilities;
import static datadog.smoketest.backend.RemoteConfig.products;
import static datadog.smoketest.springboot.OpenFeatureFixtures.APPLICATION_JAR;
import static datadog.smoketest.springboot.OpenFeatureFixtures.FLAG_CONFIGURATION_PATH;
import static datadog.smoketest.springboot.OpenFeatureFixtures.UFC_CONFIG;
import static datadog.smoketest.springboot.OpenFeatureFixtures.agent;
import static datadog.smoketest.springboot.OpenFeatureFixtures.evaluate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.remoteconfig.Product;
import datadog.smoketest.SmokeServerApp;
import datadog.smoketest.backend.MockAgentBackend;
import datadog.smoketest.springboot.OpenFeatureFixtures.Evaluation;
import datadog.trace.test.util.PollingConditions;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The SDK with the Datadog Java agent, receiving the flag configuration from Remote Configuration.
 */
class OpenFeatureProviderSmokeTest extends AbstractOpenFeatureSmokeTest {
  private static final MockAgentBackend AGENT = agent();

  static {
    // The provider initializes with the application, so the configuration is served from start-up.
    AGENT.remoteConfig().setConfig(FLAG_CONFIGURATION_PATH, UFC_CONFIG);
  }

  @RegisterExtension
  static final SmokeServerApp APP =
      SmokeServerApp.named("openfeature-provider")
          .jar(APPLICATION_JAR)
          .placeholder("agent.port", () -> Integer.toString(AGENT.port()))
          .jvmArgs(
              "-Ddd.trace.debug=true",
              "-Ddd.remote_config.enabled=true",
              "-Ddd.remote_config.url=http://localhost:${agent.port}/v0.7/config")
          .args("--server.port=${app.httpPort}")
          .env("DD_EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED", "true")
          .env("DD_EXPERIMENTAL_FLAGGING_PROVIDER_SPAN_ENRICHMENT_ENABLED", "true")
          .env("DD_FEATURE_FLAGS_CONFIGURATION_SOURCE", "remote_config")
          .backend(AGENT)
          .build();

  private static Map<String, Object> firstPoll;

  /** Captures the first Remote Configuration poll, before the backend resets for the first test. */
  @BeforeAll
  static void captureFirstPoll() {
    firstPoll = AGENT.remoteConfig().waitForRequest(request -> true);
  }

  @Override
  SmokeServerApp app() {
    return APP;
  }

  @Override
  boolean deliversExposures() {
    return true;
  }

  @Override
  void publishConfiguration() {
    AGENT.remoteConfig().setConfig(FLAG_CONFIGURATION_PATH, UFC_CONFIG);
  }

  @Test
  void asksTheAgentForFeatureFlagsOnTheFirstPoll() {
    // An already-running Agent gives a newly-started tracer one new-client cache bypass. If
    // FFE_FLAGS is missing here and only appears on a later poll, the Agent can miss the fast path.
    assertTrue(products(firstPoll).contains(Product.FFE_FLAGS.name()), "FFE_FLAGS product");
    assertTrue(
        (capabilities(firstPoll) & CAPABILITY_FFE_FLAG_CONFIGURATION_RULES) != 0,
        "flag configuration rules capability");
  }

  @Test
  void enrichesTheRequestSpan() throws IOException {
    publishConfiguration();
    Map<String, Object> request = new HashMap<>();
    request.put("flag", "flag-that-does-not-exist");
    request.put("variationType", "STRING");
    request.put("defaultValue", "fallback");
    request.put("targetingKey", "span-enrichment-smoke-test");

    Evaluation evaluation = evaluate(APP, request);

    assertEquals(200, evaluation.code, evaluation.body);
    assertEquals("fallback", evaluation.json().get("value"));
    new PollingConditions(30)
        .eventually(
            () ->
                assertTrue(
                    APP.traces().getTraces().stream()
                        .flatMap(trace -> trace.getSpans().stream())
                        .anyMatch(
                            span ->
                                span.getParentId() == 0
                                    && "{\"flag-that-does-not-exist\":\"fallback\"}"
                                        .equals(span.getMeta().get("ffe_runtime_defaults"))),
                    "the request root span carries the runtime default"));
  }
}
