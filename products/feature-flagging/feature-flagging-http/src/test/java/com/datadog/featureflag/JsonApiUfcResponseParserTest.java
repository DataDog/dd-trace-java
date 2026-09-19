package com.datadog.featureflag;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.squareup.moshi.JsonQualifier;
import com.squareup.moshi.Moshi;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.api.featureflag.ufc.v1.Shard;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Collections;
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
    assertTrue(configuration.flags.containsKey("null-split"));
    assertTrue(configuration.flags.containsKey("no-rules"));
    assertTrue(configuration.flags.containsKey("no-conditions"));
    assertTrue(configuration.flags.containsKey("no-operator"));
    assertTrue(configuration.flags.containsKey("non-semver"));
    assertTrue(configuration.flags.containsKey("valid-semver"));
    assertFalse(configuration.flags.containsKey("invalid-semver"));
    assertFalse(configuration.flags.containsKey("non-string-semver"));
    assertFalse(configuration.flags.containsKey("null-flag"));
    assertEquals(2, configuration.invalidFlags.size());
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
  void dropsFlagWithMalformedAllocationsWithoutRejectingConfig() throws Exception {
    final ServerConfiguration configuration =
        parse(
            wrap(
                configWithFlags(
                    booleanFlag("malformed-allocations", ",\"allocations\":\"not-a-list\""),
                    booleanFlag("valid-sibling", ""))));

    assertNotNull(configuration);
    assertFalse(configuration.flags.containsKey("malformed-allocations"));
    assertTrue(configuration.flags.containsKey("valid-sibling"));
    assertEquals("invalid_flag", configuration.invalidFlags.get("malformed-allocations"));
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
  void dropsFlagsWithInvalidConditionOperandsAndShardBounds() throws Exception {
    final ServerConfiguration configuration =
        parse(
            wrap(
                configWithFlags(
                    booleanFlag(
                        "non-numeric-gt",
                        allocation(
                            "non-numeric-gt",
                            "[{\"conditions\":[{\"attribute\":\"age\",\"operator\":\"GT\",\"value\":\"bad\"}]}]")),
                    booleanFlag(
                        "non-list-one-of",
                        allocation(
                            "non-list-one-of",
                            "[{\"conditions\":[{\"attribute\":\"role\",\"operator\":\"ONE_OF\",\"value\":\"admin\"}]}]")),
                    booleanFlag(
                        "negative-shard-range",
                        ",\"allocations\":[{\"key\":\"negative-shard-range\",\"rules\":[],\"splits\":[{\"variationKey\":\"on\",\"shards\":[{\"salt\":\"salt\",\"totalShards\":100,\"ranges\":[{\"start\":-1,\"end\":1}]}]}]}]"),
                    booleanFlag("valid-sibling", ""))));

    assertNotNull(configuration);
    assertFalse(configuration.flags.containsKey("non-numeric-gt"));
    assertFalse(configuration.flags.containsKey("non-list-one-of"));
    assertFalse(configuration.flags.containsKey("negative-shard-range"));
    assertTrue(configuration.flags.containsKey("valid-sibling"));
    assertEquals("invalid_flag", configuration.invalidFlags.get("non-numeric-gt"));
    assertEquals("invalid_flag", configuration.invalidFlags.get("non-list-one-of"));
    assertEquals("invalid_flag", configuration.invalidFlags.get("negative-shard-range"));
  }

  @Test
  void dropsFlagsWithInvalidShardBoundsAndConditionOperands() throws Exception {
    final ServerConfiguration configuration =
        parse(
            wrap(
                configWithFlags(
                    booleanFlag(
                        "zero-total-shards",
                        ",\"allocations\":[{\"key\":\"zero-total-shards\",\"rules\":[],\"splits\":[{\"variationKey\":\"on\",\"shards\":[{\"salt\":\"salt\",\"totalShards\":0,\"ranges\":[]}]}]}]"),
                    booleanFlag(
                        "unsigned-total-shards",
                        ",\"allocations\":[{\"key\":\"unsigned-total-shards\",\"rules\":[],\"splits\":[{\"variationKey\":\"on\",\"shards\":[{\"salt\":\"salt\",\"totalShards\":2147483648,\"ranges\":[{\"start\":2147483648,\"end\":2147483649}]}]}]}]"),
                    booleanFlag(
                        "too-many-shards",
                        ",\"allocations\":[{\"key\":\"too-many-shards\",\"rules\":[],\"splits\":[{\"variationKey\":\"on\",\"shards\":[{\"salt\":\"salt\",\"totalShards\":4294967296,\"ranges\":[]}]}]}]"),
                    booleanFlag(
                        "missing-ranges",
                        ",\"allocations\":[{\"key\":\"missing-ranges\",\"rules\":[],\"splits\":[{\"variationKey\":\"on\",\"shards\":[{\"salt\":\"salt\",\"totalShards\":1}]}]}]"),
                    booleanFlag(
                        "null-shard",
                        ",\"allocations\":[{\"key\":\"null-shard\",\"rules\":[],\"splits\":[{\"variationKey\":\"on\",\"shards\":[null]}]}]"),
                    booleanFlag(
                        "null-range",
                        ",\"allocations\":[{\"key\":\"null-range\",\"rules\":[],\"splits\":[{\"variationKey\":\"on\",\"shards\":[{\"salt\":\"salt\",\"totalShards\":1,\"ranges\":[null]}]}]}]"),
                    booleanFlag(
                        "negative-range-end",
                        ",\"allocations\":[{\"key\":\"negative-range-end\",\"rules\":[],\"splits\":[{\"variationKey\":\"on\",\"shards\":[{\"salt\":\"salt\",\"totalShards\":1,\"ranges\":[{\"start\":0,\"end\":-1}]}]}]}]"),
                    booleanFlag(
                        "non-boolean-is-null",
                        allocation(
                            "non-boolean-is-null",
                            "[{\"conditions\":[{\"attribute\":\"enabled\",\"operator\":\"IS_NULL\",\"value\":\"false\"}]}]")),
                    booleanFlag(
                        "valid-condition-operands",
                        allocation(
                            "valid-condition-operands",
                            "[{\"conditions\":[{\"attribute\":\"age\",\"operator\":\"LT\",\"value\":1},{\"attribute\":\"role\",\"operator\":\"ONE_OF\",\"value\":[\"admin\"]},{\"attribute\":\"enabled\",\"operator\":\"IS_NULL\",\"value\":true}]}]")))));

    assertNotNull(configuration);
    assertFalse(configuration.flags.containsKey("zero-total-shards"));
    assertTrue(configuration.flags.containsKey("unsigned-total-shards"));
    assertEquals(
        2_147_483_648L,
        Integer.toUnsignedLong(
            configuration
                .flags
                .get("unsigned-total-shards")
                .allocations
                .get(0)
                .splits
                .get(0)
                .shards
                .get(0)
                .totalShards));
    assertEquals(
        2_147_483_648L,
        Integer.toUnsignedLong(
            configuration
                .flags
                .get("unsigned-total-shards")
                .allocations
                .get(0)
                .splits
                .get(0)
                .shards
                .get(0)
                .ranges
                .get(0)
                .start));
    assertEquals(
        2_147_483_649L,
        Integer.toUnsignedLong(
            configuration
                .flags
                .get("unsigned-total-shards")
                .allocations
                .get(0)
                .splits
                .get(0)
                .shards
                .get(0)
                .ranges
                .get(0)
                .end));
    assertFalse(configuration.flags.containsKey("too-many-shards"));
    assertFalse(configuration.flags.containsKey("missing-ranges"));
    assertFalse(configuration.flags.containsKey("null-shard"));
    assertFalse(configuration.flags.containsKey("null-range"));
    assertFalse(configuration.flags.containsKey("negative-range-end"));
    assertFalse(configuration.flags.containsKey("non-boolean-is-null"));
    assertTrue(configuration.flags.containsKey("valid-condition-operands"));
  }

  @Test
  void shardAdapterFactoryRejectsQualifiedShard() {
    assertNull(
        UniversalFlagConfigParser.ShardAdapter.FACTORY.create(
            Shard.class,
            Collections.singleton(QualifiedShard.class.getAnnotation(ShardQualifier.class)),
            new Moshi.Builder().build()));
  }

  @JsonQualifier
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  private @interface ShardQualifier {}

  @ShardQualifier
  private static final class QualifiedShard {}

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
