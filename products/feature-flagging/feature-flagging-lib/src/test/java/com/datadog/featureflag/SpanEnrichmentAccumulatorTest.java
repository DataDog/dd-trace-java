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
    // The platform JsonWriter escapes '/' as backslash-slash and non-ASCII as a backslash-u escape.
    // That differs from the JS reference bytes but is round-trip-equivalent: all consumers
    // JSON-parse these tags (backend Jackson, system-tests json.loads), so byte-parity is not
    // required. '/' matters in practice because ffe_subjects_enc values are base64 (may contain
    // '/').
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
  void encoderNullAndNonSortedInput() {
    assertEquals("", ULeb128Encoder.encodeDeltaVarint(null), "null → empty string");
    // A non-SortedSet input must still encode identically (it is sorted internally).
    final java.util.Set<Integer> unsorted =
        new java.util.HashSet<>(java.util.Arrays.asList(130, 100, 128, 108));
    assertEquals("ZAgUAg==", ULeb128Encoder.encodeDeltaVarint(unsorted));
  }

  @Test
  void serialIdDedupeAtCap() {
    final SpanEnrichmentAccumulator acc = new SpanEnrichmentAccumulator();
    for (int i = 0; i < SpanEnrichmentAccumulator.MAX_SERIAL_IDS; i++) {
      acc.addSerialId(i);
    }
    acc.addSerialId(0); // already present + at cap → no-op, not dropped as "new"
    acc.addSerialId(9999); // new + at cap → dropped
    assertEquals(SpanEnrichmentAccumulator.MAX_SERIAL_IDS, acc.serialIdsView().size());
    assertTrue(acc.serialIdsView().contains(0));
    assertFalse(acc.serialIdsView().contains(9999));
  }

  @Test
  void subjectNullKeyIgnoredAndPerSubjectDedupeAtCap() {
    final SpanEnrichmentAccumulator acc = new SpanEnrichmentAccumulator();
    acc.addSubject(null, 1); // null targeting key → ignored
    assertEquals(0, acc.subjectCount());
    for (int i = 0; i < SpanEnrichmentAccumulator.MAX_EXPERIMENTS_PER_SUBJECT; i++) {
      acc.addSubject("s", i);
    }
    acc.addSubject("s", 0); // existing subject, at exp cap, id present → no-op
    acc.addSubject("s", 9999); // existing subject, at exp cap, id new → dropped
    assertEquals(1, acc.subjectCount());
  }

  @Test
  void stringifyDefaultCoversAllNativeShapes() {
    // list containing Double, Long, Short, Byte, null, and a nested Map — exercises every
    // writeJsonValue branch (Number/double, Integer|Long|Short|Byte/long, null, Map, Iterable).
    final Object nested =
        java.util.Arrays.asList(
            1.5d, 3L, (short) 7, (byte) 2, null, Collections.singletonMap("k", "v"));
    assertEquals(
        "[1.5,3,7,2,null,{\"k\":\"v\"}]", SpanEnrichmentAccumulator.stringifyDefault(nested));

    // Java array routes through isArray → serialized via its (non-JSON) string form, but must not
    // throw; assert it produced a quoted string.
    final String arr = SpanEnrichmentAccumulator.stringifyDefault(new int[] {1, 2});
    assertTrue(arr.startsWith("\"") && arr.endsWith("\""));

    // Character scalar (CharSequence/Character branch).
    assertEquals("x", SpanEnrichmentAccumulator.stringifyDefault('x'));
  }

  @Test
  void runtimeDefaultTruncationIsSurrogateSafe() {
    // 63 ASCII chars then an astral emoji (a surrogate pair): truncating to 64 would split the
    // pair at index 63, so utf8SafeTruncate must drop the dangling high surrogate → 63 chars.
    final StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 63; i++) {
      sb.append('a');
    }
    sb.append("😀"); // 😀 (high + low surrogate)
    final SpanEnrichmentAccumulator acc = new SpanEnrichmentAccumulator();
    acc.addDefault("flag", sb.toString());
    final String tag = acc.toSpanTags().get(SpanEnrichmentAccumulator.TAG_RUNTIME_DEFAULTS);
    assertEquals("{\"flag\":\"" + sb.substring(0, 63) + "\"}", tag);
  }

  @Test
  void noDataWhenEmpty() {
    final SpanEnrichmentAccumulator acc = new SpanEnrichmentAccumulator();
    assertFalse(acc.hasData());
    final Map<String, String> tags = acc.toSpanTags();
    assertTrue(tags.isEmpty());
  }
}
