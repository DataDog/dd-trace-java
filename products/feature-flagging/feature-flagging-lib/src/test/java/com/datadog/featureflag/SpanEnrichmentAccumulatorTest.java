package com.datadog.featureflag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Write-tier codec + accumulator suite. The encoding, limits, and tag shapes are FROZEN against the
 * Node reference ({@code dd-trace-js#8343}); the golden vector {@code {100,108,128,130} ->
 * "ZAgUAg=="} anchors byte-for-byte cross-SDK parity.
 */
class SpanEnrichmentAccumulatorTest {

  // Test-only decode oracle for the ULEB128 delta-varint codec — the production code only encodes.
  private static SortedSet<Integer> decodeDeltaVarint(final String encoded) {
    final SortedSet<Integer> result = new TreeSet<>();
    if (encoded == null || encoded.isEmpty()) {
      return result;
    }
    final byte[] bytes = Base64.getDecoder().decode(encoded);
    int previous = 0;
    int index = 0;
    while (index < bytes.length) {
      long value = 0;
      int shift = 0;
      while (true) {
        final byte b = bytes[index++];
        value |= ((long) (b & 0x7F)) << shift;
        if ((b & 0x80) == 0) {
          break;
        }
        shift += 7;
      }
      previous += (int) value;
      result.add(previous);
    }
    return result;
  }

  @Test
  void codecGoldenVectorAndRoundTrip() {
    final SortedSet<Integer> ids = new TreeSet<>();
    ids.add(100);
    ids.add(108);
    ids.add(128);
    ids.add(130);
    final String encoded = ULeb128Encoder.encodeDeltaVarint(ids);
    assertEquals("ZAgUAg==", encoded, "golden vector must match the frozen Node contract");
    assertEquals(ids, decodeDeltaVarint(encoded));
    assertEquals("", ULeb128Encoder.encodeDeltaVarint(Collections.emptySet()));
    final SortedSet<Integer> withDup = new TreeSet<>(ids);
    withDup.add(100);
    assertEquals(
        encoded, ULeb128Encoder.encodeDeltaVarint(withDup), "duplicates do not change bytes");
  }

  @Test
  void flagsEncTagFromAccumulatedSerialIds() {
    final SpanEnrichmentAccumulator acc = new SpanEnrichmentAccumulator();
    acc.addSerialId(100);
    acc.addSerialId(108);
    acc.addSerialId(128);
    acc.addSerialId(130);
    acc.addSerialId(100); // dedupe
    assertTrue(acc.hasData());
    assertEquals("ZAgUAg==", acc.toSpanTags().get(SpanEnrichmentAccumulator.TAG_FLAGS_ENC));
  }

  @Test
  void max200SerialIdsEnforced() {
    final SpanEnrichmentAccumulator acc = new SpanEnrichmentAccumulator();
    for (int i = 0; i < 300; i++) {
      acc.addSerialId(i);
    }
    assertEquals(
        SpanEnrichmentAccumulator.MAX_SERIAL_IDS,
        acc.serialIdsView().size(),
        "serial ids must be capped at 200");
  }

  @Test
  void subjectAndPerSubjectExperimentCaps() {
    // per-subject experiment cap: 20 max
    final SpanEnrichmentAccumulator acc = new SpanEnrichmentAccumulator();
    for (int i = 0; i < 25; i++) {
      acc.addSubject("subjectX", i);
    }
    final SortedSet<Integer> decoded =
        decodeDeltaVarint(
            acc.toSpanTags()
                .get(SpanEnrichmentAccumulator.TAG_SUBJECTS_ENC)
                .replaceAll("^\\{\"[a-f0-9]+\":\"", "")
                .replaceAll("\"\\}$", ""));
    assertEquals(SpanEnrichmentAccumulator.MAX_EXPERIMENTS_PER_SUBJECT, decoded.size());

    // subject cap: 10 max distinct subjects
    final SpanEnrichmentAccumulator acc2 = new SpanEnrichmentAccumulator();
    for (int i = 0; i < 15; i++) {
      acc2.addSubject("subject-" + i, i);
    }
    assertEquals(SpanEnrichmentAccumulator.MAX_SUBJECTS, acc2.subjectCount());
  }

  @Test
  void runtimeDefaultJsonStringifyAndTruncation() {
    // native object -> JSON, not toString
    assertEquals(
        "{\"a\":\"b\"}",
        SpanEnrichmentAccumulator.stringifyDefault(Collections.singletonMap("a", "b")));
    // scalar string -> as-is
    assertEquals("hello", SpanEnrichmentAccumulator.stringifyDefault("hello"));
    // null -> "null"
    assertEquals("null", SpanEnrichmentAccumulator.stringifyDefault(null));
    // native list -> JSON array
    assertEquals(
        "[\"a\",2,true]",
        SpanEnrichmentAccumulator.stringifyDefault(java.util.Arrays.asList("a", 2, true)));

    // 64-char truncation
    final StringBuilder longValue = new StringBuilder();
    for (int i = 0; i < 100; i++) {
      longValue.append('x');
    }
    final SpanEnrichmentAccumulator acc = new SpanEnrichmentAccumulator();
    acc.addDefault("flag", longValue.toString());
    final String tag = acc.toSpanTags().get(SpanEnrichmentAccumulator.TAG_RUNTIME_DEFAULTS);
    final String expectedValue =
        longValue.substring(0, SpanEnrichmentAccumulator.MAX_DEFAULT_VALUE_LENGTH);
    assertEquals("{\"flag\":\"" + expectedValue + "\"}", tag);

    // first-wins
    acc.addDefault("flag", "second");
    assertEquals(1, acc.defaultCount());
  }

  @Test
  void jsonUsesPlatformWriterEscaping() {
    // The platform JsonWriter escapes '/' -> \/ and non-ASCII -> \uXXXX. That differs from the JS
    // reference bytes but is round-trip-equivalent: all consumers JSON-parse these tags (backend
    // Jackson, system-tests json.loads), so byte-parity is not required. '/' matters in practice
    // because ffe_subjects_enc values are base64 (which can contain '/').
    assertEquals(
        "{\"h\":\"a\\/b\"}",
        SpanEnrichmentAccumulator.toJsonObject(Collections.singletonMap("h", "a/b")));
    assertEquals(
        "{\"k\":\"caf\\u00E9\"}",
        SpanEnrichmentAccumulator.toJsonObject(Collections.singletonMap("k", "café")));
    // nested structured runtime default goes through the same writer
    assertEquals(
        "{\"a\":\"x\\/y\"}",
        SpanEnrichmentAccumulator.stringifyDefault(Collections.singletonMap("a", "x/y")));
  }

  @Test
  void maxDefaultsEnforced() {
    final SpanEnrichmentAccumulator acc = new SpanEnrichmentAccumulator();
    for (int i = 0; i < 10; i++) {
      acc.addDefault("flag-" + i, "v");
    }
    assertEquals(SpanEnrichmentAccumulator.MAX_DEFAULTS, acc.defaultCount());
  }

  @Test
  void noDataWhenEmpty() {
    final SpanEnrichmentAccumulator acc = new SpanEnrichmentAccumulator();
    assertFalse(acc.hasData());
    final Map<String, String> tags = acc.toSpanTags();
    assertTrue(tags.isEmpty());
  }
}
