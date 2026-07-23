package datadog.trace.core.otlp.common;

import static datadog.communication.ddagent.TracerVersion.TRACER_VERSION;
import static datadog.trace.api.config.GeneralConfig.ENV;
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME;
import static datadog.trace.api.config.GeneralConfig.TAGS;
import static datadog.trace.api.config.GeneralConfig.VERSION;
import static datadog.trace.api.config.OtlpConfig.OTEL_TRACES_SPAN_METRICS_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_REPORT_HOSTNAME;
import static datadog.trace.core.otlp.common.OtlpResourceAttributes.datadogResourceAttributes;
import static datadog.trace.core.otlp.common.OtlpResourceAttributes.traceResourceAttributes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.json.JsonMapper;
import datadog.trace.api.Config;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for {@link OtlpResourceJson#buildResourceFragment}, mirroring {@link OtlpResourceProtoTest}
 * to keep the proto and JSON encoders in parity.
 */
class OtlpResourceJsonTest {

  // ── test data ─────────────────────────────────────────────────────────────

  private static Properties props(String... keyValues) {
    Properties props = new Properties();
    for (int i = 0; i < keyValues.length; i += 2) {
      props.setProperty(keyValues[i], keyValues[i + 1]);
    }
    return props;
  }

  private static Map<String, String> attrs(String... keyValues) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      map.put(keyValues[i], keyValues[i + 1]);
    }
    map.put("telemetry.sdk.name", "datadog");
    map.put("telemetry.sdk.version", TRACER_VERSION);
    map.put("telemetry.sdk.language", "java");
    return map;
  }

  static Stream<Arguments> resourceFragmentCases() {
    return Stream.of(
        Arguments.of(
            "service not set, no env, no version, no tags",
            props(),
            attrs("service.name", Config.get().getServiceName())),
        Arguments.of(
            "custom service name, no env, no version, no tags",
            props(SERVICE_NAME, "my-service"),
            attrs("service.name", "my-service")),
        Arguments.of(
            "env set to empty string",
            props(SERVICE_NAME, "my-service", ENV, ""),
            attrs("service.name", "my-service")),
        Arguments.of(
            "env set to non-empty value",
            props(SERVICE_NAME, "my-service", ENV, "prod"),
            attrs("service.name", "my-service", "deployment.environment.name", "prod")),
        Arguments.of(
            "version set to empty string",
            props(SERVICE_NAME, "my-service", VERSION, ""),
            attrs("service.name", "my-service")),
        Arguments.of(
            "version set to non-empty value",
            props(SERVICE_NAME, "my-service", VERSION, "1.0.0"),
            attrs("service.name", "my-service", "service.version", "1.0.0")),
        Arguments.of(
            "tags as comma-separated key:value pairs",
            props(SERVICE_NAME, "my-service", TAGS, "region:us-east,team:platform"),
            attrs(
                "service.name", "my-service",
                "region", "us-east",
                "team", "platform")),
        Arguments.of(
            "report-hostname enabled",
            props(SERVICE_NAME, "my-service", TRACE_REPORT_HOSTNAME, "true"),
            attrs("service.name", "my-service", "host.name", Config.get().getHostName())),
        Arguments.of(
            "service, env, version, and tags all set",
            props(
                SERVICE_NAME,
                "my-service",
                ENV,
                "staging",
                VERSION,
                "2.0.0",
                TAGS,
                "region:eu-west,"
                    + "service:ignored-service,"
                    + "env:ignored-env,"
                    + "version:ignored-version,"
                    + "SERVICE:ignored-service,"
                    + "ENV:ignored-env,"
                    + "VERSION:ignored-version,"
                    + "service.name:ignored-service,"
                    + "deployment.environment.name:ignored-env,"
                    + "service.version:ignored-version,"
                    + "SERVICE.NAME:ignored-service,"
                    + "DEPLOYMENT.ENVIRONMENT.NAME:ignored-env,"
                    + "SERVICE.VERSION:ignored-version,"
                    + "telemetry.sdk.name:ignored-sdk,"
                    + "telemetry.sdk.version:ignored-version,"
                    + "telemetry.sdk.language:ignored-language"),
            attrs(
                "service.name", "my-service",
                "deployment.environment.name", "staging",
                "service.version", "2.0.0",
                "region", "eu-west")));
  }

  // ── tests ─────────────────────────────────────────────────────────────────

  @ParameterizedTest(name = "{0}")
  @MethodSource("resourceFragmentCases")
  void testBuildResourceFragment(
      String caseName, Properties properties, Map<String, String> expectedAttributes)
      throws IOException {
    Config config = Config.get(properties);
    String fragment = OtlpResourceJson.buildResourceFragment(config, Collections.emptyMap());

    Map<String, String> actualAttributes = parseResourceAttributes(fragment);
    assertEquals(expectedAttributes, actualAttributes, "For case: " + caseName);
  }

  /**
   * The datadog-attrs variant carries {@code datadog.runtime_id}; the plain variant omits it.
   * (Process tags are emitted only when the experimental process-tag propagation is enabled, so
   * they aren't asserted here.)
   */
  @Test
  void datadogResourceAttributesVariantCarriesRuntimeId() throws IOException {
    Config config = Config.get(props(SERVICE_NAME, "my-service"));

    Map<String, String> withDatadog =
        parseResourceAttributes(
            OtlpResourceJson.buildResourceFragment(config, datadogResourceAttributes(config)));
    Map<String, String> plain =
        parseResourceAttributes(
            OtlpResourceJson.buildResourceFragment(config, Collections.emptyMap()));

    assertTrue(
        withDatadog.containsKey("datadog.runtime_id"),
        "datadog-attrs variant carries datadog.runtime_id");
    assertEquals(
        config.getRuntimeId(),
        withDatadog.get("datadog.runtime_id"),
        "runtime id matches the config value");
    assertFalse(plain.containsKey("datadog.runtime_id"), "plain variant omits datadog.runtime_id");
  }

  @Test
  void statsComputedVariantCarriesMarker() throws IOException {
    Config withMetrics =
        Config.get(props(SERVICE_NAME, "my-service", OTEL_TRACES_SPAN_METRICS_ENABLED, "true"));
    Config withoutMetrics = Config.get(props(SERVICE_NAME, "my-service"));

    Map<String, String> withMarker =
        parseResourceAttributes(
            OtlpResourceJson.buildResourceFragment(
                withMetrics, traceResourceAttributes(withMetrics)));
    Map<String, String> without =
        parseResourceAttributes(
            OtlpResourceJson.buildResourceFragment(
                withoutMetrics, traceResourceAttributes(withoutMetrics)));

    assertEquals(
        "true", withMarker.get("_dd.stats_computed"), "marker present when stats computed");
    assertFalse(without.containsKey("_dd.stats_computed"), "marker absent when stats not computed");
  }

  @Test
  void cannedFragmentsMatchTheirProtoCounterparts() throws IOException {
    assertEquals(
        parseResourceAttributesFromProto(OtlpResourceProto.RESOURCE_MESSAGE),
        parseResourceAttributes(OtlpResourceJson.RESOURCE_FRAGMENT));
    assertEquals(
        parseResourceAttributesFromProto(OtlpResourceProto.RESOURCE_MESSAGE_WITH_DATADOG_ATTRS),
        parseResourceAttributes(OtlpResourceJson.RESOURCE_FRAGMENT_WITH_DATADOG_ATTRS));
    assertEquals(
        parseResourceAttributesFromProto(OtlpResourceProto.TRACE_RESOURCE_MESSAGE),
        parseResourceAttributes(OtlpResourceJson.TRACE_RESOURCE_FRAGMENT));
  }

  // ── parsing helpers ───────────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private static Map<String, String> parseResourceAttributes(String fragment) throws IOException {
    Map<String, Object> resource = JsonMapper.fromJsonToMap(fragment);
    List<Object> attributes = (List<Object>) resource.get("attributes");

    Map<String, String> result = new LinkedHashMap<>();
    for (Object attribute : attributes) {
      Map<String, Object> keyValue = (Map<String, Object>) attribute;
      Map<String, Object> value = (Map<String, Object>) keyValue.get("value");
      result.put((String) keyValue.get("key"), (String) value.get("stringValue"));
    }
    return result;
  }

  private static Map<String, String> parseResourceAttributesFromProto(byte[] bytes)
      throws IOException {
    com.google.protobuf.CodedInputStream outer =
        com.google.protobuf.CodedInputStream.newInstance(bytes);
    outer.readTag();
    com.google.protobuf.CodedInputStream resource = outer.readBytes().newCodedInput();

    Map<String, String> attributes = new LinkedHashMap<>();
    while (!resource.isAtEnd()) {
      resource.readTag();
      com.google.protobuf.CodedInputStream kv = resource.readBytes().newCodedInput();
      kv.readTag();
      String key = kv.readString();
      kv.readTag();
      com.google.protobuf.CodedInputStream av = kv.readBytes().newCodedInput();
      av.readTag();
      String value = av.readString();
      attributes.put(key, value);
    }
    return attributes;
  }
}
