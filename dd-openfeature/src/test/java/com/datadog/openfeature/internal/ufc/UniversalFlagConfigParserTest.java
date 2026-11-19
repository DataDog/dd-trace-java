package com.datadog.openfeature.internal.ufc;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
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

    assertEquals(3.0, config.flags.get("f").variations.get("v").value);
  }

  @Test
  void skipsMalformedFlagAllocationsAndKeepsValidFlag() throws Exception {
    final ServerConfiguration config = deserialize(resource("malformed-allocations.json"));

    assertNotNull(config);
    assertFalse(config.flags.containsKey("malformed-flag"));
    assertTrue(config.flags.containsKey("valid-flag"));
    assertEquals("expected", config.flags.get("valid-flag").variations.get("expected").value);
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
    assertEquals("expected", config.flags.get("valid-flag").variations.get("expected").value);
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
    assertEquals("expected", config.flags.get("valid-flag").variations.get("expected").value);
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
