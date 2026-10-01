package com.datadog.openfeature.internal.ufc;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

  @Test
  void rejectsFlagWithNonBooleanEnabledAndKeepsSiblingFlag() throws Exception {
    final ServerConfiguration config =
        deserialize(
            "{\"flags\":{"
                + "\"bad\":{\"key\":\"bad\",\"enabled\":\"yes\"},"
                + "\"good\":{\"key\":\"good\",\"enabled\":true}"
                + "}}");

    assertNotNull(config);
    assertFalse(config.flags.containsKey("bad"));
    assertEquals("invalid_flag", config.invalidFlags.get("bad"));
    assertTrue(config.flags.get("good").enabled);
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
  @ParameterizedTest
  @CsvSource({"\"340132\", 340132", "1.5, 1", "-1, -1", "2147483648, 2147483647"})
  void coercesSerialIdWithoutRejectingTheFlag(final String wireValue, final int expected)
      throws Exception {
    final ServerConfiguration config = deserialize(configWithSerialId(wireValue));

    assertNotNull(config);
    assertEquals(Integer.valueOf(expected), serialIdOf(config));
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

  private static ServerConfiguration deserialize(final String json) throws Exception {
    return UniversalFlagConfigParser.parse(json.getBytes(UTF_8));
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
