package com.datadog.featureflag;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class RandomizationActivationTest {
  @ParameterizedTest
  @ValueSource(strings = {"true", "42", "[]", "{}", "\"bad\"", "\"\"", "\"2026-02-30T12:00:00Z\""})
  void rejectsMalformedActivationOnlyForAffectedFlag(final String activation) throws Exception {
    final ServerConfiguration config = parse(activation);
    assertFalse(config.flags.containsKey("target"));
    assertEquals("invalid_flag", config.invalidFlags.get("target"));
    assertTrue(config.flags.containsKey("neighbor"));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"null"})
  void acceptsUnknownActivation(final String activation) throws Exception {
    final ServerConfiguration config = parse(activation);
    assertTrue(config.invalidFlags == null || config.invalidFlags.isEmpty());
    assertNull(config.flags.get("target").allocations.get(0).randomizationActivatedAt);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"\"2026-10-19T12:00:00.123456789Z\"", "\"2026-10-19T08:00:00.123456789-04:00\""})
  void retainsPrecisionAndOffsets(final String activation) throws Exception {
    final ServerConfiguration config = parse(activation);
    assertTrue(config.invalidFlags == null || config.invalidFlags.isEmpty());
    assertEquals(
        Instant.parse("2026-10-19T12:00:00.123456789Z"),
        config.flags.get("target").allocations.get(0).randomizationActivatedAt);
  }

  private static ServerConfiguration parse(final String activation) throws Exception {
    final String json =
        "{\"flags\":{"
            + "\"target\":{\"key\":\"target\",\"enabled\":true,\"variationType\":\"STRING\","
            + "\"variations\":{},\"allocations\":[{\"key\":\"experiment\",\"doLog\":true"
            + (activation == null ? "" : ",\"randomizationActivatedAt\":" + activation)
            + "}]},\"neighbor\":{\"key\":\"neighbor\",\"enabled\":true,\"variationType\":\"STRING\","
            + "\"variations\":{},\"allocations\":[]}}}";
    return UniversalFlagConfigParser.INSTANCE.deserialize(json.getBytes(UTF_8));
  }
}
