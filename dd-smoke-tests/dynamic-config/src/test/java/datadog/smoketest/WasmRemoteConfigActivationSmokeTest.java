package datadog.smoketest;

import static datadog.environment.JavaVirtualMachine.isOracleJDK8;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_ACTIVATION;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_CUSTOM_RULES;
import static datadog.smoketest.dynamicconfig.AppSecApplication.TIMEOUT_IN_SECONDS;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import datadog.smoketest.backend.AgentBackend;
import datadog.smoketest.backend.RemoteConfig;
import datadog.smoketest.backend.Telemetry;
import datadog.smoketest.dynamicconfig.AppSecApplication;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies the full Remote Configuration workflow through the default engine: with remote config
 * enabled (the wasm engine is the default on JVM 11+), the tracer polls, subscribes products and
 * advertises capabilities through the libdatadog WASM client, and a config pushed by the backend is
 * delivered, applied (AppSec activates) and acknowledged.
 */
class WasmRemoteConfigActivationSmokeTest {
  private static final String APPLICATION_JAR =
      System.getProperty("datadog.smoketest.shadowJar.path");
  private static final List<String> ASM_RULE_PRODUCTS = asList("ASM", "ASM_DD", "ASM_DATA");

  // Inline backend owned by the app; held as a field so the test can push and read remote-config.
  static final AgentBackend agent = AgentBackend.testAgent();

  @RegisterExtension
  static final SmokeCliApp app =
      SmokeCliApp.named("wasm-remote-config")
          .mainClass(AppSecApplication.class, APPLICATION_JAR)
          .jvmArgs("-Ddd.remote_config.enabled=true", "-Ddd.remote_config.poll_interval.seconds=1")
          .backend(agent)
          .build();

  @Test
  void deliversAppliesAndAcknowledgesRemoteConfigThroughTheWasmEngine() {
    assumeFalse(isOracleJDK8(), "Telemetry product-change event flakes on Oracle JDK 8");

    RemoteConfig remoteConfig = agent.remoteConfig();
    Telemetry telemetry = agent.telemetry();

    // While AppSec is enabled but inactive, the wasm engine polls with ASM_FEATURES subscribed
    // and the ASM_ACTIVATION capability advertised.
    Map<String, Object> beforeActivation =
        remoteConfig.waitForRequest(
            request -> RemoteConfig.products(request).contains("ASM_FEATURES"), TIMEOUT_IN_SECONDS);
    assertTrue(
        hasCapability(RemoteConfig.capabilities(beforeActivation), CAPABILITY_ASM_ACTIVATION),
        "ASM_ACTIVATION advertised through the wasm engine");

    // Activate AppSec via Remote Configuration, delivered through the wasm engine.
    remoteConfig.setConfig(
        "datadog/2/ASM_FEATURES/asm_features_activation/config", "{\"asm\":{\"enabled\":true}}");

    // The tracer applies the change and reports it via a telemetry configuration event.
    telemetry.waitForFlat(
        WasmRemoteConfigActivationSmokeTest::appsecEnabledFromRemoteConfig, TIMEOUT_IN_SECONDS);

    // Now active: the wasm engine subscribes to the ASM rule products and advertises
    // ASM_CUSTOM_RULES.
    Map<String, Object> afterActivation =
        remoteConfig.waitForRequest(
            request -> RemoteConfig.products(request).containsAll(ASM_RULE_PRODUCTS),
            TIMEOUT_IN_SECONDS);
    assertTrue(
        hasCapability(RemoteConfig.capabilities(afterActivation), CAPABILITY_ASM_CUSTOM_RULES),
        "ASM_CUSTOM_RULES advertised after activation");
  }

  // A flattened telemetry event whose payload records DD_APPSEC_ENABLED=true from remote config.
  @SuppressWarnings("unchecked")
  private static boolean appsecEnabledFromRemoteConfig(Map<String, Object> event) {
    Object payload = event.get("payload");
    if (!(payload instanceof Map)) {
      return false;
    }
    Object configuration = ((Map<String, Object>) payload).get("configuration");
    if (!(configuration instanceof List)) {
      return false;
    }
    for (Object entry : (List<?>) configuration) {
      if (entry instanceof Map) {
        Map<String, Object> config = (Map<String, Object>) entry;
        if ("DD_APPSEC_ENABLED".equals(config.get("name"))
            && "true".equals(config.get("value"))
            && "remote_config".equals(config.get("origin"))) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean hasCapability(long capabilities, long flag) {
    return (capabilities & flag) != 0;
  }
}
