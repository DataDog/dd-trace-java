package com.datadog.featureflag;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonApiUfcResponseParserTest {

  @Test
  void parsesJsonApiMembersInAnyOrder() throws Exception {
    final ServerConfiguration configuration =
        parse(
            "{"
                + "\"meta\":{\"ignored\":true},"
                + "\"data\":{"
                + "\"attributes\":"
                + emptyConfig()
                + ",\"ignored\":true,"
                + "\"type\":\"universal-flag-configuration\""
                + "}"
                + "}");

    assertNotNull(configuration);
    assertEquals("Test", configuration.environment.name);
    assertTrue(configuration.flags.isEmpty());
  }

  @Test
  void rejectsRawUfc() throws Exception {
    assertNull(parse(emptyConfig()));
  }

  @Test
  void rejectsUnexpectedJsonApiType() throws Exception {
    assertNull(parse("{\"data\":{\"type\":\"other-type\",\"attributes\":" + emptyConfig() + "}}"));
  }

  @Test
  void rejectsNonStringJsonApiType() throws Exception {
    assertNull(parse("{\"data\":{\"type\":null,\"attributes\":" + emptyConfig() + "}}"));
  }

  @Test
  void rejectsConfigurationWithoutFlags() throws Exception {
    assertNull(
        parse(
            "{\"data\":{"
                + "\"type\":\"universal-flag-configuration\","
                + "\"attributes\":{\"environment\":{\"name\":\"Test\"}}"
                + "}}"));
  }

  @Test
  void rejectsNonObjectData() throws Exception {
    assertNull(parse("{\"data\":[]}"));
  }

  @Test
  void rejectsTrailingJson() {
    assertThrows(
        IOException.class,
        () ->
            parse(
                "{\"data\":{\"type\":\"universal-flag-configuration\",\"attributes\":"
                    + emptyConfig()
                    + "}}{}"));
  }

  @Test
  void preprocessesSemverComparandsAndDropsMalformedFlags() throws Exception {
    final ServerConfiguration configuration =
        parse(
            wrap(
                configWithFlags(
                    booleanFlag("no-allocations", ""),
                    booleanFlag(
                        "no-splits", ",\"allocations\":[{\"key\":\"no-splits\",\"rules\":[]}]"),
                    booleanFlag(
                        "null-split",
                        ",\"allocations\":[{\"key\":\"null-split\",\"rules\":[],\"splits\":[null]}]"),
                    booleanFlag("null-rule", allocation("null-rule", "[null]")),
                    booleanFlag("no-rules", allocation("no-rules", "")),
                    booleanFlag("no-conditions", allocation("no-conditions", "[{}]")),
                    booleanFlag(
                        "no-operator", allocation("no-operator", "[{\"conditions\":[{}]}]")),
                    booleanFlag(
                        "non-semver",
                        allocation(
                            "non-semver",
                            "[{\"conditions\":[{\"attribute\":\"version\",\"operator\":\"MATCHES\",\"value\":\"1\"}]}]")),
                    booleanFlag(
                        "valid-semver",
                        allocation(
                            "valid-semver",
                            "[{\"conditions\":[{\"attribute\":\"version\",\"operator\":\"SEMVER_EQ\",\"value\":\"1.2\"}]}]")),
                    booleanFlag(
                        "invalid-semver",
                        allocation(
                            "invalid-semver",
                            "[{\"conditions\":[{\"attribute\":\"version\",\"operator\":\"SEMVER_EQ\",\"value\":\"1.02\"}]}]")),
                    booleanFlag(
                        "non-string-semver",
                        allocation(
                            "non-string-semver",
                            "[{\"conditions\":[{\"attribute\":\"version\",\"operator\":\"SEMVER_EQ\",\"value\":1}]}]")),
                    "\"null-flag\":null")));

    assertNotNull(configuration);
    assertTrue(configuration.flags.containsKey("no-allocations"));
    assertTrue(configuration.flags.containsKey("no-splits"));
    assertFalse(configuration.flags.containsKey("null-split"));
    assertFalse(configuration.flags.containsKey("null-rule"));
    assertTrue(configuration.flags.containsKey("no-rules"));
    assertTrue(configuration.flags.containsKey("no-conditions"));
    assertFalse(configuration.flags.containsKey("no-operator"));
    assertTrue(configuration.flags.containsKey("non-semver"));
    assertTrue(configuration.flags.containsKey("valid-semver"));
    assertFalse(configuration.flags.containsKey("invalid-semver"));
    assertFalse(configuration.flags.containsKey("non-string-semver"));
    assertFalse(configuration.flags.containsKey("null-flag"));
    assertEquals(6, configuration.invalidFlags.size());
    assertEquals("invalid_flag", configuration.invalidFlags.get("null-flag"));
    assertEquals("invalid_flag", configuration.invalidFlags.get("null-split"));
    assertEquals("invalid_flag", configuration.invalidFlags.get("null-rule"));
    assertEquals("invalid_flag", configuration.invalidFlags.get("no-operator"));
    assertEquals("invalid_semver_comparand", configuration.invalidFlags.get("invalid-semver"));
    assertEquals("invalid_semver_comparand", configuration.invalidFlags.get("non-string-semver"));

    assertNotNull(
        configuration
            .flags
            .get("valid-semver")
            .allocations
            .get(0)
            .rules
            .get(0)
            .conditions
            .get(0)
            .semverComparand);
  }

  @Test
  void dropsFlagWithMissingSplitShards() throws Exception {
    final ServerConfiguration configuration =
        parse(
            wrap(
                configWithFlags(
                    booleanFlag(
                        "missing-shards",
                        ",\"allocations\":[{\"key\":\"missing-shards\",\"rules\":[],\"splits\":[{\"variationKey\":\"on\"}]}]"),
                    booleanFlag("valid-sibling", ""))));

    assertNotNull(configuration);
    assertFalse(configuration.flags.containsKey("missing-shards"));
    assertTrue(configuration.flags.containsKey("valid-sibling"));
    assertEquals("invalid_flag", configuration.invalidFlags.get("missing-shards"));
  }

  @Test
  void preservesUnsigned32BitShardRangeBounds() throws Exception {
    final ServerConfiguration configuration =
        parse(
            wrap(
                configWithFlags(
                    booleanFlag(
                        "unsigned-shard-range",
                        ",\"allocations\":[{\"key\":\"unsigned-shard-range\",\"rules\":[],\"splits\":[{\"variationKey\":\"on\",\"shards\":[{\"salt\":\"test\",\"totalShards\":4294967295,\"ranges\":[{\"start\":2147483648,\"end\":4294967295}]}]}]}]"),
                    booleanFlag(
                        "overflow-shard-range",
                        ",\"allocations\":[{\"key\":\"overflow-shard-range\",\"rules\":[],\"splits\":[{\"variationKey\":\"on\",\"shards\":[{\"salt\":\"test\",\"totalShards\":4294967295,\"ranges\":[{\"start\":0,\"end\":4294967296}]}]}]}]"),
                    booleanFlag("valid-sibling", ""))));

    assertNotNull(configuration);
    assertTrue(configuration.flags.containsKey("unsigned-shard-range"));
    assertEquals(
        2147483648L,
        configuration
            .flags
            .get("unsigned-shard-range")
            .allocations
            .get(0)
            .splits
            .get(0)
            .shards
            .get(0)
            .ranges
            .get(0)
            .start);
    assertEquals(
        4294967295L,
        configuration
            .flags
            .get("unsigned-shard-range")
            .allocations
            .get(0)
            .splits
            .get(0)
            .shards
            .get(0)
            .ranges
            .get(0)
            .end);
    assertFalse(configuration.flags.containsKey("overflow-shard-range"));
    assertEquals("invalid_flag", configuration.invalidFlags.get("overflow-shard-range"));
    assertTrue(configuration.flags.containsKey("valid-sibling"));
  }

  @Test
  void dropsFlagsWithInvalidNestedAllocationsShardsAndOperands() throws Exception {
    final ServerConfiguration configuration =
        parse(
            wrap(
                configWithFlags(
                    booleanFlag("null-allocation", ",\"allocations\":[null]"),
                    booleanFlag(
                        "zero-total-shards",
                        ",\"allocations\":[{\"key\":\"zero-total-shards\",\"rules\":[],\"splits\":[{\"variationKey\":\"on\",\"shards\":[{\"salt\":\"test\",\"totalShards\":0,\"ranges\":[]}]}]}]"),
                    booleanFlag(
                        "null-shard",
                        ",\"allocations\":[{\"key\":\"null-shard\",\"rules\":[],\"splits\":[{\"variationKey\":\"on\",\"shards\":[null]}]}]"),
                    booleanFlag(
                        "overflow-total-shards",
                        ",\"allocations\":[{\"key\":\"overflow-total-shards\",\"rules\":[],\"splits\":[{\"variationKey\":\"on\",\"shards\":[{\"salt\":\"test\",\"totalShards\":4294967296,\"ranges\":[]}]}]}]"),
                    booleanFlag(
                        "missing-ranges",
                        ",\"allocations\":[{\"key\":\"missing-ranges\",\"rules\":[],\"splits\":[{\"variationKey\":\"on\",\"shards\":[{\"salt\":\"test\",\"totalShards\":1}]}]}]"),
                    booleanFlag(
                        "invalid-is-null",
                        allocation(
                            "invalid-is-null",
                            "[{\"conditions\":[{\"attribute\":\"version\",\"operator\":\"IS_NULL\",\"value\":\"true\"}]}]")),
                    booleanFlag(
                        "invalid-one-of",
                        allocation(
                            "invalid-one-of",
                            "[{\"conditions\":[{\"attribute\":\"version\",\"operator\":\"ONE_OF\",\"value\":\"1\"}]}]")),
                    booleanFlag(
                        "invalid-gte",
                        allocation(
                            "invalid-gte",
                            "[{\"conditions\":[{\"attribute\":\"version\",\"operator\":\"GTE\",\"value\":\"1\"}]}]")),
                    booleanFlag(
                        "valid-is-null",
                        allocation(
                            "valid-is-null",
                            "[{\"conditions\":[{\"attribute\":\"version\",\"operator\":\"IS_NULL\",\"value\":true}]}]")),
                    booleanFlag(
                        "valid-one-of",
                        allocation(
                            "valid-one-of",
                            "[{\"conditions\":[{\"attribute\":\"version\",\"operator\":\"ONE_OF\",\"value\":[\"1\"]}]}]")),
                    booleanFlag(
                        "valid-gte",
                        allocation(
                            "valid-gte",
                            "[{\"conditions\":[{\"attribute\":\"version\",\"operator\":\"GTE\",\"value\":1}]}]")),
                    booleanFlag("valid-sibling", ""))));

    assertNotNull(configuration);
    assertFalse(configuration.flags.containsKey("null-allocation"));
    assertFalse(configuration.flags.containsKey("zero-total-shards"));
    assertFalse(configuration.flags.containsKey("null-shard"));
    assertFalse(configuration.flags.containsKey("overflow-total-shards"));
    assertFalse(configuration.flags.containsKey("missing-ranges"));
    assertFalse(configuration.flags.containsKey("invalid-is-null"));
    assertFalse(configuration.flags.containsKey("invalid-one-of"));
    assertFalse(configuration.flags.containsKey("invalid-gte"));
    assertTrue(configuration.flags.containsKey("valid-is-null"));
    assertTrue(configuration.flags.containsKey("valid-one-of"));
    assertTrue(configuration.flags.containsKey("valid-gte"));
    assertTrue(configuration.flags.containsKey("valid-sibling"));
    assertEquals(8, configuration.invalidFlags.size());
  }

  @Test
  void nullAttributesAreRejectedWithoutInvokingTheFlagParser() throws Exception {
    assertNull(parse("{\"data\":{\"type\":\"universal-flag-configuration\",\"attributes\":null}}"));
  }

  @Test
  void observeFullEvaluationDataDefaultsToFalseWhenAbsent() throws Exception {
    // Absent → Moshi leaves the boxed field null; the read site's Boolean.TRUE.equals(...) then
    // resolves to the privacy-preserving default (consent-off). Either null-or-false is the
    // documented invariant; assert the field never reads as true.
    final ServerConfiguration configuration = parse(wrap(emptyConfig()));
    assertNotNull(configuration);
    assertFalse(Boolean.TRUE.equals(configuration.observeFullEvaluationData));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void observeFullEvaluationDataParsesExplicitValue(final boolean value) throws Exception {
    final ServerConfiguration configuration =
        parse(wrap(configWithObserveFullEvaluationData(value)));
    assertNotNull(configuration);
    assertEquals(value, configuration.observeFullEvaluationData);
  }

  @Test
  void observeFullEvaluationDataExplicitNullDefaultsToFalseWithoutRejectingConfig()
      throws Exception {
    // An explicit null (or a wrong-typed value) for this field must not abort the whole UFC parse.
    // A pod that starts after a malformed UFC has no last-known-good, so aborting would strand
    // every flag on PROVIDER_NOT_READY (its default value). We fail closed on privacy (consent
    // stays false) but preserve availability: flags parse and evaluate.
    final ServerConfiguration configuration =
        parse(wrap(configWithRawObserveFullEvaluationData("null")));

    assertNotNull(configuration);
    assertFalse(
        configuration.observeFullEvaluationData != null && configuration.observeFullEvaluationData);
    assertNotNull(configuration.flags);
  }

  @Test
  void observeFullEvaluationDataWrongTypedStringDefaultsToFalse() throws Exception {
    // Moshi tolerates a stringified boolean like "true" via nullSafe/boxed handling: it either
    // parses as null or throws locally and leaves the field null. Either way, downstream reads
    // via Boolean.TRUE.equals(...) treat it as consent-off. The rest of the config must parse.
    final ServerConfiguration configuration =
        parse(wrap(configWithRawObserveFullEvaluationData("\"true\"")));

    assertNotNull(configuration);
    assertFalse(
        configuration.observeFullEvaluationData != null && configuration.observeFullEvaluationData);
    assertNotNull(configuration.flags);
  }

  @Test
  void observeFullEvaluationDataWrongTypedNumberDefaultsToFalse() throws Exception {
    final ServerConfiguration configuration =
        parse(wrap(configWithRawObserveFullEvaluationData("1")));

    assertNotNull(configuration);
    assertFalse(
        configuration.observeFullEvaluationData != null && configuration.observeFullEvaluationData);
    assertNotNull(configuration.flags);
  }

  private static ServerConfiguration parse(final String json) throws Exception {
    return JsonApiUfcResponseParser.INSTANCE.parse(json.getBytes(UTF_8));
  }

  private static String wrap(final String attributes) {
    return "{\"data\":{\"type\":\"universal-flag-configuration\",\"attributes\":"
        + attributes
        + "}}";
  }

  private static String configWithObserveFullEvaluationData(final boolean value) {
    return "{"
        + "\"createdAt\":\"2024-04-17T19:40:53.716Z\","
        + "\"observeFullEvaluationData\":"
        + value
        + ","
        + "\"environment\":{\"name\":\"Test\"},"
        + "\"flags\":{}"
        + "}";
  }

  private static String configWithRawObserveFullEvaluationData(final String rawJsonValue) {
    // Emit the field with a caller-controlled raw JSON value (null / "true" / 1 / ...) so we can
    // assert the parser's tolerance of malformed shapes without going through configWith's
    // boolean-typed helper.
    return "{"
        + "\"createdAt\":\"2024-04-17T19:40:53.716Z\","
        + "\"observeFullEvaluationData\":"
        + rawJsonValue
        + ","
        + "\"environment\":{\"name\":\"Test\"},"
        + "\"flags\":{}"
        + "}";
  }

  private static String configWithFlags(final String... flags) {
    return "{"
        + "\"createdAt\":\"2024-04-17T19:40:53.716Z\","
        + "\"environment\":{\"name\":\"Test\"},"
        + "\"flags\":{"
        + String.join(",", flags)
        + "}}";
  }

  private static String booleanFlag(final String key, final String allocations) {
    return "\""
        + key
        + "\":{"
        + "\"key\":\""
        + key
        + "\",\"enabled\":true,\"variationType\":\"BOOLEAN\","
        + "\"variations\":{\"on\":{\"key\":\"on\",\"value\":true}}"
        + allocations
        + "}";
  }

  private static String allocation(final String key, final String rules) {
    return ",\"allocations\":[{\"key\":\""
        + key
        + "\""
        + (rules.isEmpty() ? "" : ",\"rules\":" + rules)
        + ",\"splits\":[]}]";
  }

  private static String emptyConfig() {
    return "{"
        + "\"createdAt\":\"2024-04-17T19:40:53.716Z\","
        + "\"environment\":{\"name\":\"Test\"},"
        + "\"flags\":{}"
        + "}";
  }
}
