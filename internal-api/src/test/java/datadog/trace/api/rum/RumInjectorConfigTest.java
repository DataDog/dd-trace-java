package datadog.trace.api.rum;

import static datadog.trace.api.ConfigDefaults.DEFAULT_RUM_SITE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.rum.RumInjectorConfig.PrivacyLevel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RumInjectorConfigTest {

  @ParameterizedTest
  @CsvSource(
      value = {
        // spotless:off
      // Minimal configuration ID
      "appId | token | null              | null | null | 6 | null | null  | null  | null  | null | null  | null  | remote-config-id",
      // Using site
      "appId | token | datadoghq.com     | null | null | 6 | null | null  | null  | null  | null | null  | null  | remote-config-id",
      "appId | token | us3.datadoghq.com | null | null | 6 | null | null  | null  | null  | null | null  | null  | remote-config-id",
      "appId | token | us5.datadoghq.com | null | null | 6 | null | null  | null  | null  | null | null  | null  | remote-config-id",
      "appId | token | datadoghq.eu      | null | null | 6 | null | null  | null  | null  | null | null  | null  | remote-config-id",
      "appId | token | ap1.datadoghq.com | null | null | 6 | null | null  | null  | null  | null | null  | null  | remote-config-id",
      // Using service
      "appId | token | null              | svc  | null | 6 | null | null  | null  | null  | null | null  | null  | remote-config-id",
      // Using env
      "appId | token | null              | null | prod | 6 | null | null  | null  | null  | null | null  | null  | remote-config-id",
      // Using major version
      "appId | token | null              | null | null | 5 | null | null  | null  | null  | null | null  | null  | remote-config-id",
      // Using version
      "appId | token | null              | null | null | 6 | 1.23 | null  | null  | null  | null | null  | null  | remote-config-id",
      // Using track user interactions
      "appId | token | null              | null | null | 6 | 1.23 | true  | null  | null  | null | null  | null  | remote-config-id",
      "appId | token | null              | null | null | 6 | 1.23 | false | null  | null  | null | null  | null  | remote-config-id",
      // Using track resources
      "appId | token | null              | null | null | 6 | 1.23 | null  | true  | null  | null | null  | null  | remote-config-id",
      "appId | token | null              | null | null | 6 | 1.23 | null  | false | null  | null | null  | null  | remote-config-id",
      // Using track long task
      "appId | token | null              | null | null | 6 | 1.23 | null  | null  | true  | null | null  | null  | remote-config-id",
      "appId | token | null              | null | null | 6 | 1.23 | null  | null  | false | null | null  | null  | remote-config-id",
      // Using default privacy level
      "appId | token | null              | null | null | 6 | 1.23 | null  | null  | null  | ALLOW           | null  | null  | remote-config-id",
      "appId | token | null              | null | null | 6 | 1.23 | null  | null  | null  | MASK            | null  | null  | remote-config-id",
      "appId | token | null              | null | null | 6 | 1.23 | null  | null  | null  | MASK_USER_INPUT | null  | null  | remote-config-id",
      // Using session sample rate
      "appId | token | null              | null | null | 6 | null | null  | null  | null  | null            | 0     | null  | remote-config-id",
      "appId | token | null              | null | null | 6 | null | null  | null  | null  | null            | 1     | null  | remote-config-id",
      "appId | token | null              | null | null | 6 | null | null  | null  | null  | null            | 25.5  | null  | remote-config-id",
      "appId | token | null              | null | null | 6 | null | null  | null  | null  | null            | 100   | null  | remote-config-id",
      // Using session replay sample rate
      "appId | token | null              | null | null | 6 | null | null  | null  | null  | null            | null  | 0     | remote-config-id",
      "appId | token | null              | null | null | 6 | null | null  | null  | null  | null            | null  | 1     | remote-config-id",
      "appId | token | null              | null | null | 6 | null | null  | null  | null  | null            | null  | 25.5  | remote-config-id",
      "appId | token | null              | null | null | 6 | null | null  | null  | null  | null            | null  | 100   | remote-config-id",
      // spotless:on
      },
      delimiterString = "|",
      nullValues = "null")
  void testSnippet(
      String applicationId,
      String clientToken,
      String site,
      String service,
      String env,
      int majorVersion,
      String version,
      Boolean trackUserInteractions,
      Boolean trackResources,
      Boolean trackLongTask,
      PrivacyLevel defaultPrivacyLevel,
      Float sessionSampleRate,
      Float sessionReplaySampleRate,
      String remoteConfigurationId) {
    RumInjectorConfig injectorConfig =
        new RumInjectorConfig(
            applicationId,
            clientToken,
            site,
            service,
            env,
            majorVersion,
            version,
            trackUserInteractions,
            trackResources,
            trackLongTask,
            defaultPrivacyLevel,
            sessionSampleRate,
            sessionReplaySampleRate,
            remoteConfigurationId);

    String jsonPayload = injectorConfig.jsonPayload();
    assertTrue(jsonPayload.contains(applicationId));
    assertTrue(jsonPayload.contains("site"));
    assertTrue(jsonPayload.contains(clientToken));
    if (site == null) {
      assertTrue(jsonPayload.contains(DEFAULT_RUM_SITE));
    } else {
      assertTrue(jsonPayload.contains(site));
    }
    if (service == null) {
      assertFalse(jsonPayload.contains("service"));
    } else {
      assertTrue(jsonPayload.contains("service"));
      assertTrue(jsonPayload.contains(service));
    }
    if (env == null) {
      assertFalse(jsonPayload.contains("env"));
    } else {
      assertTrue(jsonPayload.contains("env"));
      assertTrue(jsonPayload.contains(env));
    }
    if (version == null) {
      assertFalse(jsonPayload.contains("version"));
    } else {
      assertTrue(jsonPayload.contains("version"));
      assertTrue(jsonPayload.contains(version));
    }
    if (trackUserInteractions == null) {
      assertFalse(jsonPayload.contains("trackUserInteractions"));
    } else {
      assertTrue(jsonPayload.contains("trackUserInteractions"));
    }
    if (trackResources == null) {
      assertFalse(jsonPayload.contains("trackResources"));
    } else {
      assertTrue(jsonPayload.contains("trackResources"));
    }
    if (trackLongTask == null) {
      assertFalse(jsonPayload.contains("trackLongTask"));
    } else {
      assertTrue(jsonPayload.contains("trackLongTask"));
    }
    if (defaultPrivacyLevel == null) {
      assertFalse(jsonPayload.contains("defaultPrivacyLevel"));
    } else {
      assertTrue(jsonPayload.contains("defaultPrivacyLevel"));
      assertTrue(jsonPayload.contains(defaultPrivacyLevel.toJson()));
    }
    if (sessionSampleRate == null) {
      assertFalse(jsonPayload.contains("sessionSampleRate"));
    } else {
      assertTrue(jsonPayload.contains("sessionSampleRate"));
    }
    if (sessionReplaySampleRate == null) {
      assertFalse(jsonPayload.contains("sessionReplaySampleRate"));
    } else {
      assertTrue(jsonPayload.contains("sessionReplaySampleRate"));
    }
    if (remoteConfigurationId == null) {
      assertFalse(jsonPayload.contains("remoteConfigurationId"));
    } else {
      assertTrue(jsonPayload.contains("remoteConfigurationId"));
      assertTrue(jsonPayload.contains(remoteConfigurationId));
    }
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        // spotless:off
      // Invalid application ID
      "null  | token | datadoghq.com | null | null | 6 | null | true | true | true | null | null  | null  | remote-config-id",
      "''    | token | datadoghq.com | null | null | 6 | null | true | true | true | null | null  | null  | remote-config-id",
      // Invalid client token
      "appId | null  | datadoghq.com | null | null | 6 | null | true | true | true | null | null  | null  | remote-config-id",
      "appId | ''    | datadoghq.com | null | null | 6 | null | true | true | true | null | null  | null  | remote-config-id",
      // Invalid site
      "appId | token | other.com     | null | null | 6 | null | true | true | true | null | null  | null  | remote-config-id",
      // Invalid major version
      "appId | token | datadoghq.com | null | null | 4 | null | true | true | true | null | null  | null  | remote-config-id",
      "appId | token | datadoghq.com | null | null | 7 | null | true | true | true | null | null  | null  | remote-config-id",
      // Invalid session sample rate
      "appId | token | datadoghq.com | null | null | 6 | null | true | true | true | null | -1    | null  | remote-config-id",
      "appId | token | datadoghq.com | null | null | 6 | null | true | true | true | null | -0.1  | null  | remote-config-id",
      "appId | token | datadoghq.com | null | null | 6 | null | true | true | true | null | 101   | null  | remote-config-id",
      "appId | token | datadoghq.com | null | null | 6 | null | true | true | true | null | 100.1 | null  | remote-config-id",
      // Invalid session replay sample rate
      "appId | token | datadoghq.com | null | null | 6 | null | true | true | true | null | null  | -1    | remote-config-id",
      "appId | token | datadoghq.com | null | null | 6 | null | true | true | true | null | null  | -0.1  | remote-config-id",
      "appId | token | datadoghq.com | null | null | 6 | null | true | true | true | null | null  | 101   | remote-config-id",
      "appId | token | datadoghq.com | null | null | 6 | null | true | true | true | null | null  | 100.1 | remote-config-id",
      // Invalid rates and remote configuration id
      "appId | token | datadoghq.com | null | null | 6 | null | true | true | true | null | null  | null  | null",
      // spotless:on
      },
      delimiterString = "|",
      nullValues = "null")
  void testInvalidConfig(
      String applicationId,
      String clientToken,
      String site,
      String service,
      String env,
      int majorVersion,
      String version,
      boolean trackUserInteractions,
      boolean trackResources,
      boolean trackLongTask,
      PrivacyLevel defaultPrivacyLevel,
      Float sessionSampleRate,
      Float sessionReplaySampleRate,
      String remoteConfigurationId) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RumInjectorConfig(
                applicationId,
                clientToken,
                site,
                service,
                env,
                majorVersion,
                version,
                trackUserInteractions,
                trackResources,
                trackLongTask,
                defaultPrivacyLevel,
                sessionSampleRate,
                sessionReplaySampleRate,
                remoteConfigurationId));
  }
}
