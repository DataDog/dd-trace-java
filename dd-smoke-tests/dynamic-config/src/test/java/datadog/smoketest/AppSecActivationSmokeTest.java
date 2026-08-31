package datadog.smoketest;

import static datadog.environment.JavaVirtualMachine.isOracleJDK8;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_ACTIVATION;
import static datadog.remoteconfig.Capabilities.CAPABILITY_ASM_CUSTOM_RULES;
import static datadog.remoteconfig.Product.ASM;
import static datadog.remoteconfig.Product.ASM_DATA;
import static datadog.remoteconfig.Product.ASM_DD;
import static datadog.smoketest.dynamicconfig.AppSecApplication.TIMEOUT_IN_SECONDS;
import static java.util.Collections.disjoint;
import static java.util.EnumSet.of;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import datadog.remoteconfig.Product;
import datadog.smoketest.backend.AgentBackend;
import datadog.smoketest.backend.RemoteConfig;
import datadog.smoketest.backend.Telemetry;
import datadog.smoketest.backend.TestAgentBackend;
import datadog.smoketest.dynamicconfig.AppSecApplication;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies AppSec activation via Remote Configuration. With AppSec enabled but inactive, the tracer
 * advertises only the ASM_ACTIVATION capability; once a config activating AppSec is pushed, the
 * tracer reports the change via telemetry (DD_APPSEC_ENABLED, origin {@code remote_config}) and
 * then subscribes to the ASM rule products (ASM, ASM_DD, ASM_DATA), advertising ASM_CUSTOM_RULES.
 *
 * <p>{@link AppSecApplication} just stays alive briefly while its tracer polls Remote Config, so
 * the whole activation workflow is asserted from the poll requests and telemetry captured by the
 * {@link TestAgentBackend}. Telemetry is central here, so the base's telemetry support is left
 * enabled: it flushes the app on a fast heartbeat (so the config-change event arrives within the
 * app's short lifetime) and additionally asserts telemetry is flowing.
 */
class AppSecActivationSmokeTest {
  private static final String APPLICATION_JAR =
      System.getProperty("datadog.smoketest.shadowJar.path");
  private static final EnumSet<Product> ASM_RULE_PRODUCTS = of(ASM, ASM_DD, ASM_DATA);

  // Inline backend owned by the app; held as a field so the test can push and read remote-config.
  static final AgentBackend agent = AgentBackend.testAgent();

  @RegisterExtension
  static final SmokeCliApp app =
      SmokeCliApp.named("appsec-activation")
          .mainClass(AppSecApplication.class, APPLICATION_JAR)
          .jvmArgs("-Ddd.remote_config.enabled=true", "-Ddd.remote_config.poll_interval.seconds=1")
          .backend(agent)
          .build();

  @Test
  void activatesAppSecViaRemoteConfig() {
    assumeFalse(isOracleJDK8(), "Telemetry product-change event flakes on Oracle JDK 8");

    RemoteConfig remoteConfig = agent.remoteConfig();
    Telemetry telemetry = agent.telemetry();

    // AppSec is enabled but inactive: a poll that has not subscribed to any ASM rule product yet
    // advertises the ASM_ACTIVATION capability, but not ASM_CUSTOM_RULES.
    Map<String, Object> beforeActivation =
        remoteConfig.waitForRequest(
            request -> disjoint(decodeProducts(request), ASM_RULE_PRODUCTS), TIMEOUT_IN_SECONDS);
    long capabilities = RemoteConfig.capabilities(beforeActivation);
    assertTrue(hasCapability(capabilities, CAPABILITY_ASM_ACTIVATION), "ASM_ACTIVATION advertised");
    assertFalse(
        hasCapability(capabilities, CAPABILITY_ASM_CUSTOM_RULES),
        "ASM_CUSTOM_RULES not advertised while inactive");

    // Activate AppSec via Remote Config.
    remoteConfig.setConfig(
        "datadog/2/ASM_FEATURES/asm_features_activation/config", "{\"asm\":{\"enabled\":true}}");

    // The tracer reports the applied change via a telemetry configuration event.
    telemetry.waitForFlat(
        AppSecActivationSmokeTest::appsecEnabledFromRemoteConfig, TIMEOUT_IN_SECONDS);

    // Now active: the tracer subscribes to the ASM rule products and advertises ASM_CUSTOM_RULES.
    Map<String, Object> afterActivation =
        remoteConfig.waitForRequest(
            request -> decodeProducts(request).containsAll(ASM_RULE_PRODUCTS), TIMEOUT_IN_SECONDS);
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

  // The products a Remote Config poll subscribes to, decoded from the wire strings into typed
  // Product values (the tracer serializes them from this same enum).
  private static Set<Product> decodeProducts(Map<String, Object> request) {
    Set<Product> products = EnumSet.noneOf(Product.class);
    for (String name : RemoteConfig.products(request)) {
      products.add(Product.valueOf(name));
    }
    return products;
  }

  private static boolean hasCapability(long capabilities, long capability) {
    return (capabilities & capability) != 0;
  }
}
