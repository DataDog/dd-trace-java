package com.datadog.openfeature.internal.ufc;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/** Parses raw UFC documents, as delivered by Remote Configuration. */
class UniversalFlagConfigParserTest {

  @Test
  void parsesEmptyConfiguration() throws Exception {
    final ServerConfiguration config = deserialize(emptyConfig());

    assertNotNull(config);
    assertTrue(config.flags.isEmpty());
    assertTrue(config.invalidFlags.isEmpty());
  }

  @Test
  void parsesNullDocumentAsNoConfiguration() throws Exception {
    assertNull(deserialize("null"));
  }

  @Test
  void rejectsNonObjectDocument() {
    assertThrows(IOException.class, () -> deserialize("[]"));
  }

  @Test
  void rejectsDuplicateKeys() {
    assertThrows(IOException.class, () -> deserialize("{\"format\":\"a\",\"format\":\"b\"}"));
  }

  @Test
  void rejectsWrongTypedFlagMap() {
    assertThrows(IOException.class, () -> deserialize("{\"flags\":[]}"));
  }

  @TableTest({
    "scenario              | flag                                                                                                ",
    "overflowing variant   | '{\"key\":\"f\",\"variations\":{\"v\":{\"key\":\"v\",\"value\":1e400}}}'                            ",
    "negative overflow     | '{\"key\":\"f\",\"variations\":{\"v\":{\"key\":\"v\",\"value\":-1e400}}}'                           ",
    "overflowing condition | '{\"key\":\"f\",\"allocations\":[{\"key\":\"a\",\"rules\":[{\"conditions\":[{\"value\":1e400}]}]}]}'"
  })
  void rejectsNonFiniteNumbers(final String flag) {
    assertThrows(IOException.class, () -> deserialize("{\"flags\":{\"f\":" + flag + "}}"));
  }

  @TableTest({
    "scenario | enabled  ",
    "string   | '\"yes\"'",
    "number   | '1'      ",
    "null     | 'null'   "
  })
  void rejectsFlagWithNonBooleanEnabledAndKeepsSiblingFlag(final String enabled) throws Exception {
    final ServerConfiguration config =
        deserialize(
            "{\"flags\":{"
                + "\"bad\":{\"key\":\"bad\",\"enabled\":"
                + enabled
                + "},"
                + "\"good\":{\"key\":\"good\",\"enabled\":true}"
                + "}}");

    assertNotNull(config);
    assertFalse(config.flags.containsKey("bad"));
    assertEquals("invalid_flag", config.invalidFlags.get("bad"));
    assertTrue(config.flags.get("good").enabled);
  }

  @Test
  void readsAbsentEnabledAsDisabled() throws Exception {
    final ServerConfiguration config = deserialize("{\"flags\":{\"f\":{\"key\":\"f\"}}}");

    assertNotNull(config);
    assertFalse(config.flags.get("f").enabled);
    assertTrue(config.invalidFlags.isEmpty());
  }

  @Test
  void readsVariantNumbersAsDoubles() throws Exception {
    final ServerConfiguration config =
        deserialize(
            "{\"flags\":{\"f\":{\"key\":\"f\",\"enabled\":true,\"variationType\":\"INTEGER\","
                + "\"variations\":{\"v\":{\"key\":\"v\",\"value\":3}}}}}");

    assertEquals(3.0, config.flags.get("f").variations.get("v").value());
  }

  @Test
  void skipsMalformedFlagAllocationsAndKeepsValidFlag() throws Exception {
    final ServerConfiguration config = deserialize(resource("malformed-allocations.json"));

    assertNotNull(config);
    assertFalse(config.flags.containsKey("malformed-flag"));
    assertTrue(config.flags.containsKey("valid-flag"));
    assertEquals("expected", config.flags.get("valid-flag").variations.get("expected").value());
  }

  @Test
  void parsesSplitSerialId() throws Exception {
    final ServerConfiguration config = deserialize(configWithSerialId("340132"));

    assertNotNull(config);
    assertEquals(Integer.valueOf(340132), serialIdOf(config));
  }

  @Test
  void parsesSplitSerialIdZero() throws Exception {
    final ServerConfiguration config = deserialize(configWithSerialId("0"));

    assertNotNull(config);
    assertEquals(Integer.valueOf(0), serialIdOf(config));
  }

  @Test
  void parsesAbsentSplitSerialIdAsNull() throws Exception {
    final ServerConfiguration config = deserialize(configWithSerialId(null));

    assertNotNull(config);
    assertNull(serialIdOf(config));
  }

  @Test
  void parsesNullSplitSerialIdAsNull() throws Exception {
    final ServerConfiguration config = deserialize(configWithSerialId("null"));

    assertNotNull(config);
    assertNull(serialIdOf(config));
  }

  @Test
  void skipsFlagWithUncoercibleSerialIdAndKeepsSiblingFlag() throws Exception {
    final ServerConfiguration config = deserialize(configWithSiblingSerialIds("true"));

    assertNotNull(config);
    assertFalse(config.flags.containsKey("malformed-flag"));
    assertEquals("invalid_flag", config.invalidFlags.get("malformed-flag"));
    assertTrue(config.flags.containsKey("valid-flag"));
    assertEquals(Integer.valueOf(7), serialIdOf(config));
  }

  /**
   * Records how leniently the per-flag value reader coerces a serial id, so a future change to the
   * parse path is visible here. The values come from the compiler-validated UFC, so the SDK adds no
   * validation of its own; what matters is that a bad one never rejects the sibling flag.
   */
  @TableTest({
    "scenario           | wireValue    | expected  ",
    "numeric string     | '\"340132\"' | 340132    ",
    "fraction truncated | '1.5'        | 1         ",
    "negative           | '-1'         | -1        ",
    "overflow saturates | '2147483648' | 2147483647"
  })
  void coercesSerialIdWithoutRejectingTheFlag(final String wireValue, final int expected)
      throws Exception {
    final ServerConfiguration config = deserialize(configWithSerialId(wireValue));

    assertNotNull(config);
    assertEquals(Integer.valueOf(expected), serialIdOf(config));
  }

  @Test
  void parsesSplitFeatures() throws Exception {
    final ServerConfiguration config =
        deserialize(
            configWithFeatures(
                "[{\"key\": \"holdout.key\", \"value\": \"q4-global\", \"destinations\": [\"HOOK\"]},"
                    + " {\"key\": \"holdout.weight\", \"value\": 0.5,"
                    + " \"destinations\": [\"EXPOSURE\", \"EVALUATION\"]},"
                    + " {\"key\": \"holdout.should_include_in_holdout_analysis\", \"value\": true,"
                    + " \"destinations\": [\"HOOK\", \"SOME_FUTURE_DESTINATION\"]}]"));

    final List<Feature> features = featuresOf(config);
    assertEquals(3, features.size());
    assertFeature(features.get(0), "holdout.key", "q4-global", "HOOK");
    assertFeature(features.get(1), "holdout.weight", 0.5, "EXPOSURE", "EVALUATION");
    assertFeature(
        features.get(2),
        "holdout.should_include_in_holdout_analysis",
        true,
        "HOOK",
        "SOME_FUTURE_DESTINATION");
    assertEquals(Integer.valueOf(7), serialIdOf(config));
  }

  @Test
  void parsesAbsentSplitFeaturesAsNull() throws Exception {
    final ServerConfiguration config = deserialize(configWithFeatures(null));

    assertNull(featuresOf(config));
    assertEquals(Integer.valueOf(7), serialIdOf(config));
  }

  @Test
  void dropsMalformedSplitFeaturesAndKeepsTheFlag() throws Exception {
    final String hook = ", \"destinations\": [\"HOOK\"]";
    final ServerConfiguration config =
        deserialize(
            configWithFeatures(
                "[{\"key\": \"object-value\", \"value\": {\"nested\": true}"
                    + hook
                    + "},"
                    + " {\"key\": \"array-value\", \"value\": [1]"
                    + hook
                    + "},"
                    + " {\"key\": \"null-value\", \"value\": null"
                    + hook
                    + "},"
                    + " {\"key\": \"\", \"value\": \"empty key\""
                    + hook
                    + "},"
                    + " {\"key\": 3, \"value\": \"numeric key\""
                    + hook
                    + "},"
                    + " {\"value\": \"missing key\""
                    + hook
                    + "},"
                    + " {\"key\": \"missing value\""
                    + hook
                    + "},"
                    + " {\"key\": \"no-destinations\", \"value\": \"dropped\"},"
                    + " {\"key\": \"empty-destinations\", \"value\": \"dropped\", \"destinations\": []},"
                    + " {\"key\": \"destinations-not-a-list\", \"value\": \"dropped\","
                    + " \"destinations\": \"HOOK\"},"
                    + " {\"key\": \"only-invalid-destinations\", \"value\": \"dropped\","
                    + " \"destinations\": [1, \"\", null, {\"name\": \"HOOK\"}]},"
                    + " \"not-an-object\","
                    + " {\"key\": \"holdout.key\", \"value\": \"q4-global\","
                    + " \"destinations\": [1, \"HOOK\", \"\"], \"destination\": \"HOOK\"}]"));

    assertTrue(config.flags.containsKey("valid-flag"));
    final List<Feature> features = featuresOf(config);
    assertEquals(1, features.size());
    assertFeature(features.get(0), "holdout.key", "q4-global", "HOOK");
  }

  @Test
  void ignoresSplitFeaturesThatAreNotAList() throws Exception {
    final ServerConfiguration config =
        deserialize(configWithFeatures("{\"holdout.key\": \"q4-global\"}"));

    assertTrue(config.flags.containsKey("valid-flag"));
    assertNull(featuresOf(config));
    assertEquals(Integer.valueOf(7), serialIdOf(config));
  }

  private static void assertFeature(
      final Feature feature, final String key, final Object value, final String... destinations) {
    assertEquals(key, feature.key);
    assertEquals(value, feature.value);
    assertEquals(asList(destinations), feature.destinations);
  }

  private static List<Feature> featuresOf(final ServerConfiguration config) {
    return config.flags.get("valid-flag").allocations.get(0).splits.get(0).features;
  }

  /** Inserts the raw features JSON; null omits the key. */
  private static String configWithFeatures(final String featuresJson) throws IOException {
    final String json = resource("split-features.json");
    return featuresJson == null
        ? json.replace("\"features\": \"${features}\",", "")
        : json.replace("\"${features}\"", featuresJson);
  }

  private static Integer serialIdOf(final ServerConfiguration config) {
    return config.flags.get("valid-flag").allocations.get(0).splits.get(0).serialId;
  }

  private static String configWithSerialId(final String serialIdJson) throws IOException {
    return withSerialId(resource("serial-id.json"), serialIdJson);
  }

  /** A malformed serial id must bind to its own flag and leave the sibling flag intact. */
  private static String configWithSiblingSerialIds(final String malformedSerialIdJson)
      throws IOException {
    return withSerialId(resource("sibling-serial-ids.json"), malformedSerialIdJson);
  }

  /** Inserts the raw wire value so the parser sees its original JSON type; null omits the key. */
  private static String withSerialId(final String json, final String serialIdJson) {
    return serialIdJson == null
        ? json.replace("\"serialId\": \"${serialId}\",", "")
        : json.replace("\"${serialId}\"", serialIdJson);
  }

  @Test
  void ignoresUnknownTopLevelFields() throws Exception {
    final ServerConfiguration config = deserialize(resource("unknown-top-level-fields.json"));

    assertNotNull(config);
    assertEquals("2024-04-17T19:40:53.716Z", config.createdAt);
    assertEquals("SERVER", config.format);
    assertNotNull(config.environment);
    assertEquals("Test", config.environment.name);
    assertTrue(config.flags.isEmpty());
  }

  @Test
  void parsesAllocationWindowDatesWithMicrosecondPrecision() throws Exception {
    final ServerConfiguration config = deserialize(resource("allocation-window-dates.json"));

    final Allocation allocation = config.flags.get("dated-flag").allocations.get(0);
    assertEquals(Instant.parse("2023-01-01T00:00:00.123456Z"), allocation.startAt);
    assertEquals(Instant.parse("2023-01-02T00:00:00.987654Z"), allocation.endAt);
  }

  @Test
  void rejectsTrailingJson() {
    assertThrows(IOException.class, () -> deserialize(emptyConfig() + "{}"));
  }

  @Test
  void acceptsNestingUpToTheLimit() throws Exception {
    assertNotNull(deserialize(nestedConfig(31)));
  }

  @Test
  void rejectsNestingBeyondTheLimit() {
    assertThrows(IOException.class, () -> deserialize(nestedConfig(32)));
    assertThrows(
        IOException.class,
        () ->
            UniversalFlagConfigParser.parseJsonApi(
                ("{\"data\":" + nestedConfig(32) + "}").getBytes(UTF_8)));
  }

  @Test
  void skipsUnknownOperatorFlagAndKeepsValidFlag() throws Exception {
    final ServerConfiguration config = deserialize(resource("unknown-operator.json"));

    assertNotNull(config);
    assertFalse(config.flags.containsKey("operator-grease-flag"));
    assertTrue(config.flags.containsKey("valid-flag"));
    assertEquals("expected", config.flags.get("valid-flag").variations.get("expected").value());
  }

  @Test
  void allowsNullFlagMap() throws Exception {
    final ServerConfiguration config = deserialize(resource("null-flags.json"));

    assertNotNull(config);
    assertNull(config.flags);
  }

  @Test
  void skipsNullFlagAndKeepsValidFlag() throws Exception {
    final ServerConfiguration config = deserialize(resource("null-flag.json"));

    assertNotNull(config);
    assertFalse(config.flags.containsKey("null-flag"));
    assertTrue(config.flags.containsKey("valid-flag"));
    assertEquals("expected", config.flags.get("valid-flag").variations.get("expected").value());
  }

  @TableTest({
    "scenario                       | value                            | expectedInstant                 ",
    "utc second                     | '2023-01-01T00:00:00Z'           | '2023-01-01T00:00:00Z'          ",
    "utc end of year                | '2023-12-31T23:59:59Z'           | '2023-12-31T23:59:59Z'          ",
    "leap day                       | '2024-02-29T12:00:00Z'           | '2024-02-29T12:00:00Z'          ",
    "millisecond precision          | '2023-01-01T00:00:00.000Z'       | '2023-01-01T00:00:00Z'          ",
    "three fractional digits        | '2023-06-15T14:30:45.123Z'       | '2023-06-15T14:30:45.123Z'      ",
    "six fractional digits          | '2023-06-15T14:30:45.123456Z'    | '2023-06-15T14:30:45.123456Z'   ",
    "six fractional digits distinct | '2023-06-15T14:30:45.235982Z'    | '2023-06-15T14:30:45.235982Z'   ",
    "nine fractional digits         | '2023-06-15T14:30:45.123456789Z' | '2023-06-15T14:30:45.123456789Z'",
    "one fractional digit           | '2023-06-15T14:30:45.1Z'         | '2023-06-15T14:30:45.100Z'      ",
    "two fractional digits          | '2023-06-15T14:30:45.12Z'        | '2023-06-15T14:30:45.120Z'      ",
    "positive offset                | '2023-01-01T01:00:00+01:00'      | '2023-01-01T00:00:00Z'          ",
    "negative offset                | '2023-01-01T00:00:00-05:00'      | '2023-01-01T05:00:00Z'          ",
    "date only                      | '2023-01-01'                     |                                 ",
    "invalid                        | 'invalid-date'                   |                                 ",
    "empty string                   | ''                               |                                 ",
    "not a date                     | 'not-a-date'                     |                                 ",
    "slash date                     | '2023/01/01T00:00:00Z'           |                                 ",
    "null                           |                                  |                                 "
  })
  void testInstantParsing(final String value, final String expectedInstant) throws Exception {
    final String startAt = value == null ? "null" : "\"" + value + "\"";
    final ServerConfiguration config =
        deserialize(
            "{\"flags\":{\"f\":{\"key\":\"f\",\"enabled\":true,"
                + "\"allocations\":[{\"key\":\"a\",\"startAt\":"
                + startAt
                + "}]}}}");

    final Instant parsed = config.flags.get("f").allocations.get(0).startAt;
    if (expectedInstant == null) {
      assertNull(parsed);
    } else {
      assertNotNull(parsed);
      assertEquals(expectedInstant, parsed.toString());
    }
  }

  @TableTest({
    "scenario      | field     | date   ",
    "boolean start | 'startAt' | 'true' ",
    "object start  | 'startAt' | '{}'   ",
    "array start   | 'startAt' | '[]'   ",
    "boolean end   | 'endAt'   | 'false'",
    "object end    | 'endAt'   | '{}'   ",
    "array end     | 'endAt'   | '[]'   "
  })
  void rejectsFlagWithNonStringAllocationDateAndKeepsSiblingFlag(
      final String field, final String date) throws Exception {
    final ServerConfiguration config =
        deserialize(
            "{\"flags\":{"
                + "\"bad\":{\"key\":\"bad\",\"enabled\":true,"
                + "\"allocations\":[{\"key\":\"a\",\""
                + field
                + "\":"
                + date
                + "}]},"
                + "\"good\":{\"key\":\"good\",\"enabled\":true}"
                + "}}");

    assertNotNull(config);
    assertFalse(config.flags.containsKey("bad"));
    assertEquals("invalid_flag", config.invalidFlags.get("bad"));
    assertTrue(config.flags.containsKey("good"));
  }

  @Test
  void readsNumericAllocationDatesAsUnbounded() throws Exception {
    final ServerConfiguration config =
        deserialize(
            "{\"flags\":{\"f\":{\"key\":\"f\",\"enabled\":true,"
                + "\"allocations\":[{\"key\":\"a\",\"startAt\":1672531200,\"endAt\":1.5}]}}}");

    final Allocation allocation = config.flags.get("f").allocations.get(0);
    assertNull(allocation.startAt);
    assertNull(allocation.endAt);
    assertTrue(config.invalidFlags.isEmpty());
  }

  @Test
  void indexesOneOfListsOfTwoOrMoreElements() throws Exception {
    final ServerConfiguration config =
        deserialize(
            "{\"flags\":{\"f\":{\"key\":\"f\",\"enabled\":true,\"variationType\":\"STRING\","
                + "\"variations\":{\"in\":{\"key\":\"in\",\"value\":\"in\"}},"
                + "\"allocations\":[{\"key\":\"a\",\"rules\":[{\"conditions\":["
                + "{\"attribute\":\"x\",\"operator\":\"ONE_OF\",\"value\":[\"1\",\"2\"]},"
                + "{\"attribute\":\"x\",\"operator\":\"NOT_ONE_OF\",\"value\":[\"3\",4]},"
                + "{\"attribute\":\"x\",\"operator\":\"ONE_OF\",\"value\":[\"5\"]}]}],"
                + "\"splits\":[]}]}}}");

    final List<ConditionConfiguration> conditions =
        config.flags.get("f").allocations.get(0).rules.get(0).conditions;
    assertEquals(MembershipIndex.MATCH, conditions.get(0).membership.lookup(2));
    assertEquals(MembershipIndex.MATCH, conditions.get(1).membership.lookup(4));
    assertNull(conditions.get(2).membership);
  }

  @Test
  void sharesOneRegexMatcherForEachPattern() throws Exception {
    final ServerConfiguration config =
        deserialize(
            regexConfig(
                regexFlag("one", "^[a-z]+@site\\\\.test$"),
                regexFlag("two", "^[a-z]+@site\\\\.test$"),
                regexFlag("three", "@other\\\\.test$")));
    final RegexMatcher one = regex(config, "one");
    assertSame(one, regex(config, "two"));
    assertEquals(RegexMatcher.Kind.ENGINE, one.kind());
    assertEquals(RegexMatcher.Kind.ENDS_WITH, regex(config, "three").kind());
  }

  @Test
  void dropsAFlagWithRegexSyntaxThatRustRejects() throws Exception {
    final ServerConfiguration config =
        deserialize(
            regexConfig(regexFlag("lookahead", "(?=a)a"), regexFlag("valid", "@other\\\\.test$")));
    assertFalse(config.flags.containsKey("lookahead"));
    assertEquals("unsupported_regex", config.invalidFlags.get("lookahead"));
    assertEquals(RegexMatcher.Kind.ENDS_WITH, regex(config, "valid").kind());
  }

  /** A pattern that does not compile is not cached, so each flag that uses it is dropped. */
  @Test
  void dropsEveryFlagThatSharesAnUnsupportedPattern() throws Exception {
    final ServerConfiguration config =
        deserialize(
            regexConfig(
                regexFlag("first", "(?=a)a"),
                regexFlag("second", "(?=a)a"),
                regexFlag("valid", "@other\\\\.test$")));
    assertFalse(config.flags.containsKey("first"));
    assertFalse(config.flags.containsKey("second"));
    assertEquals("unsupported_regex", config.invalidFlags.get("first"));
    assertEquals("unsupported_regex", config.invalidFlags.get("second"));
    assertTrue(config.flags.containsKey("valid"));
  }

  private static String regexConfig(final String... flags) {
    return "{\"createdAt\":\"2024-04-17T19:40:53.716Z\",\"environment\":{\"name\":\"Test\"},"
        + "\"flags\":{"
        + String.join(",", flags)
        + "}}";
  }

  private static String regexFlag(final String key, final String pattern) {
    return "\""
        + key
        + "\":{\"key\":\""
        + key
        + "\",\"enabled\":true,\"variationType\":\"BOOLEAN\","
        + "\"variations\":{\"on\":{\"key\":\"on\",\"value\":true}},"
        + "\"allocations\":[{\"key\":\"a\",\"rules\":[{\"conditions\":["
        + "{\"attribute\":\"email\",\"operator\":\"MATCHES\",\"value\":\""
        + pattern
        + "\"}]}],\"splits\":[{\"variationKey\":\"on\",\"shards\":[]}],\"doLog\":true}]}";
  }

  private static RegexMatcher regex(final ServerConfiguration config, final String key) {
    return config.flags.get(key).allocations.get(0).rules.get(0).conditions.get(0).regex;
  }

  @Test
  void sharesOneInstanceOfEachRepeatedText() throws Exception {
    final ServerConfiguration config =
        deserialize(
            "{\"flags\":{\"flag-a\":{\"key\":\"flag-a\",\"enabled\":true,"
                + "\"variationType\":\"BOOLEAN\","
                + "\"variations\":{\"on\":{\"key\":\"on\",\"value\":true}},"
                + "\"allocations\":[{\"key\":\"alloc\",\"rules\":["
                + "{\"conditions\":[{\"operator\":\"ONE_OF\",\"attribute\":\"country\","
                + "\"value\":[\"Aa\",\"BB\"]}]},"
                + "{\"conditions\":[{\"operator\":\"ONE_OF\",\"attribute\":\"country\","
                + "\"value\":[\"Aa\"]}]}],"
                + "\"splits\":[{\"variationKey\":\"on\",\"shards\":[]}]}]}}}");

    final String flagKey = config.flags.keySet().iterator().next();
    final Flag flag = config.flags.get("flag-a");
    assertSame(flagKey, flag.key);

    final String variationKey = flag.variations.keySet().iterator().next();
    final Allocation allocation = flag.allocations.get(0);
    assertSame(variationKey, flag.variations.get("on").key);
    assertSame(variationKey, allocation.splits.get(0).variationKey);

    final ConditionConfiguration first = allocation.rules.get(0).conditions.get(0);
    final ConditionConfiguration second = allocation.rules.get(1).conditions.get(0);
    assertSame(first.attribute, second.attribute);
    // Equal hashes, different texts: still two values.
    assertEquals(asList("Aa", "BB"), first.value);
    assertSame(((List<?>) first.value).get(0), ((List<?>) second.value).get(0));
  }

  @Test
  void keepsEscapedNonAsciiAndLongStrings() throws Exception {
    final StringBuilder longValue = new StringBuilder();
    for (int i = 0; i < 20_000; i++) {
      longValue.append((char) ('a' + i % 26));
    }
    final ServerConfiguration config =
        deserialize(
            "{\"flags\":{\"f\":{\"key\":\"f\",\"enabled\":true,\"variationType\":\"STRING\","
                + "\"variations\":{"
                + "\"escaped\":{\"key\":\"escaped\",\"value\":\"q\\\"b\\\\s\\u00e9\\uD83D\\uDE00\"},"
                + "\"long\":{\"key\":\"long\",\"value\":\""
                + longValue
                + "\"},"
                + "\"empty\":{\"key\":\"empty\",\"value\":\"\"}},"
                + "\"allocations\":[]}}}");

    final Flag flag = config.flags.get("f");
    assertEquals("q\"b\\s\u00e9\uD83D\uDE00", flag.variations.get("escaped").value);
    assertEquals(longValue.toString(), flag.variations.get("long").value);
    assertEquals("", flag.variations.get("empty").value);
  }

  @Test
  void storesListsAndMapsAtTheirExactSize() throws Exception {
    final ServerConfiguration config =
        deserialize(
            "{\"flags\":{\"f\":{\"key\":\"f\",\"enabled\":true,\"variationType\":\"STRING\","
                + "\"variations\":{\"a\":{\"key\":\"a\",\"value\":\"a\"},"
                + "\"b\":{\"key\":\"b\",\"value\":\"b\"}},"
                + "\"allocations\":[{\"key\":\"alloc\",\"rules\":[],"
                + "\"splits\":[{\"variationKey\":\"a\",\"shards\":[],"
                + "\"features\":[{\"key\":\"k\",\"value\":\"v\",\"destinations\":[\"HOOK\"]}]}]}]}}}");

    final Flag flag = config.flags.get("f");
    final Allocation allocation = flag.allocations.get(0);
    // Every parsed list is the same class, so the evaluator's loops stay monomorphic.
    final Class<?> listClass = CompactList.class;
    assertTrue(allocation.rules.isEmpty());
    assertEquals(listClass, allocation.rules.getClass());
    assertEquals(listClass, flag.allocations.getClass());
    assertEquals(listClass, allocation.splits.getClass());
    assertEquals(listClass, allocation.splits.get(0).features.getClass());
    assertEquals(2, flag.variations.size());
    assertEquals("b", flag.variations.get("b").value);
    assertThrows(UnsupportedOperationException.class, () -> flag.variations.put("c", null));
    assertThrows(UnsupportedOperationException.class, () -> config.flags.remove("f"));
    final List<Feature> features = allocation.splits.get(0).features;
    assertEquals(1, features.size());
    assertThrows(UnsupportedOperationException.class, () -> features.add(null));
    assertThrows(
        UnsupportedOperationException.class, () -> features.get(0).destinations.add("EXPOSURE"));
  }

  @Test
  void keepsNullVariantsAndNullListElements() throws Exception {
    final ServerConfiguration config =
        deserialize(
            "{\"flags\":{\"f\":{\"key\":\"f\",\"enabled\":true,\"variationType\":\"STRING\","
                + "\"variations\":{\"a\":{\"key\":\"a\",\"value\":\"a\"},\"gone\":null},"
                + "\"allocations\":[{\"key\":\"alloc\",\"rules\":[{\"conditions\":["
                + "{\"operator\":\"ONE_OF\",\"attribute\":\"x\",\"value\":[\"a\",null,\"b\"]},"
                + "{\"operator\":\"ONE_OF\",\"attribute\":\"y\",\"value\":[null]},"
                + "{\"operator\":\"ONE_OF\",\"attribute\":\"z\",\"value\":[]}]}],"
                + "\"splits\":[]}]}}}");

    final Flag flag = config.flags.get("f");
    assertTrue(flag.variations.containsKey("gone"));
    assertNull(flag.variations.get("gone"));
    assertNull(flag.variations.get(null));
    final List<ConditionConfiguration> conditions = flag.allocations.get(0).rules.get(0).conditions;
    assertEquals(asList("a", null, "b"), conditions.get(0).value);
    assertEquals(Collections.singletonList(null), conditions.get(1).value);
    assertEquals(Collections.emptyList(), conditions.get(2).value);
  }

  private static ServerConfiguration deserialize(final String json) throws Exception {
    return UniversalFlagConfigParser.parse(json.getBytes(UTF_8));
  }

  private static String nestedConfig(final int arrays) {
    final StringBuilder json = new StringBuilder("{\"flags\":{},\"ignored\":");
    for (int i = 0; i < arrays; i++) {
      json.append('[');
    }
    json.append('0');
    for (int i = 0; i < arrays; i++) {
      json.append(']');
    }
    return json.append('}').toString();
  }

  private static String emptyConfig() throws IOException {
    return resource("empty-config.json");
  }

  private static String resource(final String name) throws IOException {
    try (InputStream stream =
        UniversalFlagConfigParserTest.class.getResourceAsStream("/remote-config/" + name)) {
      assertNotNull(stream, "Missing remote-config fixture: " + name);
      return new String(stream.readAllBytes(), UTF_8);
    }
  }
}
