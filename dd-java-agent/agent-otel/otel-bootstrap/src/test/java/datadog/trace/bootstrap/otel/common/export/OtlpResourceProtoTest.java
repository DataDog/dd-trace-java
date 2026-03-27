package datadog.trace.bootstrap.otel.common.export;

import static datadog.trace.api.config.GeneralConfig.ENV;
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME;
import static datadog.trace.api.config.GeneralConfig.TAGS;
import static datadog.trace.api.config.GeneralConfig.VERSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import datadog.trace.api.Config;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for {@link OtlpResourceProto#buildResourceMessage}.
 *
 * <p>Each test creates a {@link Config} from a {@link Properties} instance, calls {@link
 * OtlpResourceProto#buildResourceMessage}, then extracts the byte array and verifies its content
 * against the OpenTelemetry protobuf encoding defined in {@code
 * opentelemetry/proto/resource/v1/resource.proto}.
 *
 * <p>Relevant proto field numbers:
 *
 * <pre>
 *   Resource { repeated KeyValue attributes = 1; }
 *   KeyValue { string key = 1; AnyValue value = 2; }
 *   AnyValue { string string_value = 1; }
 * </pre>
 */
class OtlpResourceProtoTest {

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
    return map;
  }

  static Stream<Arguments> resourceMessageCases() {
    return Stream.of(
        // service not set: should use the auto-detected name
        Arguments.of(
            "service not set, no env, no version, no tags",
            props(),
            attrs("service.name", Config.get().getServiceName())),
        // custom service name
        Arguments.of(
            "custom service name, no env, no version, no tags",
            props(SERVICE_NAME, "my-service"),
            attrs("service.name", "my-service")),
        // env set to empty string: no deployment.environment.name written;
        Arguments.of(
            "env set to empty string",
            props(SERVICE_NAME, "my-service", ENV, ""),
            attrs("service.name", "my-service")),
        // env set to non-empty value: deployment.environment.name written;
        Arguments.of(
            "env set to non-empty value",
            props(SERVICE_NAME, "my-service", ENV, "prod"),
            attrs("service.name", "my-service", "deployment.environment.name", "prod")),
        // version set to empty string: no service.version written;
        Arguments.of(
            "version set to empty string",
            props(SERVICE_NAME, "my-service", VERSION, ""),
            attrs("service.name", "my-service")),
        // version set to non-empty value: service.version written;
        Arguments.of(
            "version set to non-empty value",
            props(SERVICE_NAME, "my-service", VERSION, "1.0.0"),
            attrs("service.name", "my-service", "service.version", "1.0.0")),
        // tags as comma-separated key:value pairs (no env or version)
        Arguments.of(
            "tags as comma-separated key:value pairs",
            props(SERVICE_NAME, "my-service", TAGS, "region:us-east,team:platform"),
            attrs(
                "service.name", "my-service",
                "region", "us-east",
                "team", "platform")),
        // all config values set together
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
                    + "SERVICE.VERSION:ignored-version"),
            attrs(
                "service.name", "my-service",
                "deployment.environment.name", "staging",
                "service.version", "2.0.0",
                "region", "eu-west")));
  }

  // ── test ─────────────────────────────────────────────────────────────────

  @ParameterizedTest(name = "{0}")
  @MethodSource("resourceMessageCases")
  void testBuildResourceMessage(
      String caseName, Properties properties, Map<String, String> expectedAttributes)
      throws IOException {
    Config config = Config.get(properties);
    byte[] bytes = OtlpResourceProto.buildResourceMessage(config);

    Map<String, String> actualAttributes = parseResourceAttributes(bytes);
    assertEquals(expectedAttributes, actualAttributes, "For case: " + caseName);
  }

  // ── parsing helpers ───────────────────────────────────────────────────────

  /**
   * Parses the resource message bytes into an attribute map while validating the protobuf wire
   * format (field numbers and wire types) of every field read.
   *
   * <p>{@code buildResourceMessage} returns a length-prefixed message with an outer tag (field 1,
   * LEN wire type) followed by the Resource body size and body. Read the outer tag, then iterate
   * over all {@code Resource.attributes} (field 1, LEN wire type). Each attribute is a {@code
   * KeyValue} whose {@code value} is an {@code AnyValue} containing a {@code string_value}.
   */
  private static Map<String, String> parseResourceAttributes(byte[] bytes) throws IOException {
    // Read the outer tag (field 1, LEN wire type) that wraps the Resource body
    CodedInputStream outer = CodedInputStream.newInstance(bytes);
    int outerTag = outer.readTag();
    assertEquals(1, WireFormat.getTagFieldNumber(outerTag), "outer field is Resource (field 1)");
    assertEquals(WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.getTagWireType(outerTag));
    CodedInputStream resource = outer.readBytes().newCodedInput();

    Map<String, String> attributes = new LinkedHashMap<>();
    while (!resource.isAtEnd()) {
      // Each attribute is Resource.attributes (field 1, LEN wire type)
      int tag = resource.readTag();
      assertEquals(1, WireFormat.getTagFieldNumber(tag), "Resource.attributes is field 1");
      assertEquals(WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.getTagWireType(tag));

      // Read the full KeyValue body
      CodedInputStream kv = resource.readBytes().newCodedInput();

      String key = readKeyField(kv);
      CodedInputStream av = readAnyValueField(kv);

      // Read AnyValue.string_value (field 1, LEN)
      int avTag = av.readTag();
      assertEquals(1, WireFormat.getTagFieldNumber(avTag), "AnyValue.string_value is field 1");
      assertEquals(WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.getTagWireType(avTag));
      String value = av.readString();
      assertTrue(av.isAtEnd(), "no extra fields in AnyValue");
      assertTrue(kv.isAtEnd(), "no extra fields in KeyValue");

      attributes.put(key, value);
    }
    return attributes;
  }

  /** Reads the {@code KeyValue.key} field (field 1, LEN) and returns the string value. */
  private static String readKeyField(CodedInputStream kv) throws IOException {
    int tag = kv.readTag();
    assertEquals(1, WireFormat.getTagFieldNumber(tag), "KeyValue.key is field 1");
    assertEquals(WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.getTagWireType(tag));
    return kv.readString();
  }

  /**
   * Reads the {@code KeyValue.value} field (field 2, LEN) and returns a stream over the {@code
   * AnyValue} body.
   */
  private static CodedInputStream readAnyValueField(CodedInputStream kv) throws IOException {
    int tag = kv.readTag();
    assertEquals(2, WireFormat.getTagFieldNumber(tag), "KeyValue.value is field 2");
    assertEquals(WireFormat.WIRETYPE_LENGTH_DELIMITED, WireFormat.getTagWireType(tag));
    return kv.readBytes().newCodedInput();
  }
}
