package com.datadog.featureflag;

import datadog.json.JsonWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Per-local-root-span accumulator for APM feature-flag span enrichment.
 *
 * <p>Holds the serial ids, hashed subjects, and runtime defaults captured during flag evaluation
 * for a single local trace fragment. The limits, dedupe semantics, truncation, and output tag
 * shapes are FROZEN against the Node reference ({@code dd-trace-js#8343}) — see {@link
 * ULeb128Encoder}.
 *
 * <p>Instances are created lazily and held in a {@link SpanEnrichmentStates} store, keyed by the
 * local-root span object. The agent-side {@link SpanEnrichmentWriter} writes (from the flag-eval
 * seam); the write interceptor ({@link SpanEnrichmentInterceptor}) reads and clears. When the
 * span-enrichment gate is off, no seam events are dispatched, so no store and no accumulator are
 * ever created and there is no idle per-span overhead.
 *
 * <p>Runtime-default values arrive already unwrapped to native Java types (the capture side unwraps
 * any OpenFeature {@code Value} before crossing the seam), so this class has no OpenFeature
 * dependency.
 *
 * <p>Output tag shapes:
 *
 * <ul>
 *   <li>{@code ffe_flags_enc} — a bare base64 string (delta-varint of the serial ids)
 *   <li>{@code ffe_subjects_enc} — a JSON object string {@code {"<sha256hex>": "<base64>", ...}}
 *   <li>{@code ffe_runtime_defaults} — a JSON object string {@code {"<flagKey>": "<value>", ...}}
 * </ul>
 */
final class SpanEnrichmentAccumulator {

  static final int MAX_SERIAL_IDS = 200;
  static final int MAX_SUBJECTS = 10;
  static final int MAX_EXPERIMENTS_PER_SUBJECT = 20;
  static final int MAX_DEFAULTS = 5;
  static final int MAX_DEFAULT_VALUE_LENGTH = 64;

  static final String TAG_FLAGS_ENC = "ffe_flags_enc";
  static final String TAG_SUBJECTS_ENC = "ffe_subjects_enc";
  static final String TAG_RUNTIME_DEFAULTS = "ffe_runtime_defaults";

  // dedupe is structural (a Set); sorted for deterministic encoding.
  private final TreeSet<Integer> serialIds = new TreeSet<>();
  // sha256hex(targetingKey) -> serial ids. LinkedHashMap for stable iteration order.
  private final Map<String, TreeSet<Integer>> subjects = new LinkedHashMap<>();
  // flagKey -> value string (first-wins, truncated to MAX_DEFAULT_VALUE_LENGTH).
  private final Map<String, String> defaults = new LinkedHashMap<>();

  /** Adds a serial id, dropping silently once {@link #MAX_SERIAL_IDS} is reached. */
  synchronized void addSerialId(final int id) {
    if (serialIds.size() >= MAX_SERIAL_IDS && !serialIds.contains(id)) {
      return;
    }
    serialIds.add(id);
  }

  /**
   * Records that the given targeting key was exposed to the experiment identified by {@code id}.
   * The targeting key is SHA-256-hashed before storage. Enforces both the subject cap ({@link
   * #MAX_SUBJECTS}) and the per-subject experiment cap ({@link #MAX_EXPERIMENTS_PER_SUBJECT}).
   */
  synchronized void addSubject(final String targetingKey, final int id) {
    if (targetingKey == null) {
      return;
    }
    final String hashed = ULeb128Encoder.hashTargetingKey(targetingKey);
    final TreeSet<Integer> existing = subjects.get(hashed);
    if (existing != null) {
      if (existing.size() >= MAX_EXPERIMENTS_PER_SUBJECT && !existing.contains(id)) {
        return;
      }
      existing.add(id);
      return;
    }
    if (subjects.size() >= MAX_SUBJECTS) {
      return;
    }
    final TreeSet<Integer> ids = new TreeSet<>();
    ids.add(id);
    subjects.put(hashed, ids);
  }

  /**
   * Records a runtime-default value for {@code flagKey} (first-wins). Structured values (Map/List)
   * are serialized to JSON (NOT {@code toString()}); the result is truncated to {@link
   * #MAX_DEFAULT_VALUE_LENGTH}.
   */
  synchronized void addDefault(final String flagKey, final Object value) {
    if (flagKey == null) {
      return;
    }
    if (defaults.containsKey(flagKey)) {
      return; // first-wins
    }
    if (defaults.size() >= MAX_DEFAULTS) {
      return;
    }
    String valueStr = stringifyDefault(value);
    if (valueStr.length() > MAX_DEFAULT_VALUE_LENGTH) {
      valueStr = utf8SafeTruncate(valueStr, MAX_DEFAULT_VALUE_LENGTH);
    }
    defaults.put(flagKey, valueStr);
  }

  /**
   * @return true when there is at least one serial id or runtime default to write. Subjects are not
   *     checked because a subject is never recorded without its serial id.
   */
  synchronized boolean hasData() {
    return !serialIds.isEmpty() || !defaults.isEmpty();
  }

  /**
   * Builds the {@code ffe_*} span tags from the accumulated state. Empty groups are omitted.
   *
   * @return a map of tag name to tag value (a subset of {@code ffe_flags_enc}, {@code
   *     ffe_subjects_enc}, {@code ffe_runtime_defaults})
   */
  synchronized Map<String, String> toSpanTags() {
    final Map<String, String> tags = new LinkedHashMap<>();
    if (!serialIds.isEmpty()) {
      final String encoded = ULeb128Encoder.encodeDeltaVarint(serialIds);
      if (!encoded.isEmpty()) {
        tags.put(TAG_FLAGS_ENC, encoded);
      }
    }
    if (!subjects.isEmpty()) {
      final Map<String, String> encodedSubjects = new LinkedHashMap<>();
      for (final Map.Entry<String, TreeSet<Integer>> entry : subjects.entrySet()) {
        encodedSubjects.put(entry.getKey(), ULeb128Encoder.encodeDeltaVarint(entry.getValue()));
      }
      tags.put(TAG_SUBJECTS_ENC, toJsonObject(encodedSubjects));
    }
    if (!defaults.isEmpty()) {
      tags.put(TAG_RUNTIME_DEFAULTS, toJsonObject(defaults));
    }
    return tags;
  }

  // ---- helpers (visible for tests) ----

  /**
   * Mirrors the Node {@code (typeof value === 'object' && value !== null) ? JSON.stringify(value) :
   * String(value)} rule: structured values (Map/List/array) are JSON-stringified; scalars use their
   * string form; {@code null} becomes the bare {@code null}.
   *
   * <p>The value has already been unwrapped to a native Java type by the capture side (any
   * OpenFeature {@code Value} is converted to Map/List/scalar before the seam), so no OpenFeature
   * type ever reaches here.
   */
  static String stringifyDefault(final Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof Map || value instanceof Iterable || value.getClass().isArray()) {
      return toJsonValue(value);
    }
    if (value instanceof CharSequence || value instanceof Character) {
      return value.toString();
    }
    // Numbers / booleans — their string form matches what Node's String(value) emits for these
    // scalar cases.
    return String.valueOf(value);
  }

  /** UTF-8-safe truncation: never split a surrogate pair at the {@code maxChars} boundary. */
  static String utf8SafeTruncate(final String value, final int maxChars) {
    if (value.length() <= maxChars) {
      return value;
    }
    int end = maxChars;
    if (Character.isHighSurrogate(value.charAt(end - 1))) {
      end--; // drop the dangling high surrogate rather than emit a broken pair
    }
    return value.substring(0, end);
  }

  /**
   * Serializes a String-&gt;String map to a compact JSON object string using the platform {@link
   * JsonWriter}.
   *
   * <p>Consumers of these tags parse them as JSON (the backend enricher via Jackson, the parametric
   * system-tests via {@code json.loads}), so the writer's escaping (e.g. {@code /} → {@code \/},
   * non-ASCII → {@code \\uXXXX}) is round-trip-equivalent and byte-parity with the JS reference is
   * not required.
   */
  static String toJsonObject(final Map<String, String> map) {
    try (JsonWriter writer = new JsonWriter()) {
      writer.beginObject();
      for (final Map.Entry<String, String> entry : map.entrySet()) {
        writer.name(entry.getKey()).value(entry.getValue());
      }
      writer.endObject();
      return writer.toString();
    }
  }

  private static String toJsonValue(final Object value) {
    try (JsonWriter writer = new JsonWriter()) {
      writeJsonValue(writer, value);
      return writer.toString();
    }
  }

  @SuppressWarnings("unchecked")
  private static void writeJsonValue(final JsonWriter writer, final Object value) {
    // Callers pass values already unwrapped to native form by the capture side, so no OpenFeature
    // Value ever reaches here.
    if (value == null) {
      writer.nullValue();
    } else if (value instanceof Map) {
      writer.beginObject();
      for (final Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        writer.name(String.valueOf(entry.getKey()));
        writeJsonValue(writer, entry.getValue());
      }
      writer.endObject();
    } else if (value instanceof Iterable) {
      writer.beginArray();
      for (final Object element : (Iterable<Object>) value) {
        writeJsonValue(writer, element);
      }
      writer.endArray();
    } else if (value instanceof Boolean) {
      writer.value((Boolean) value);
    } else if (value instanceof Integer
        || value instanceof Long
        || value instanceof Short
        || value instanceof Byte) {
      writer.value(((Number) value).longValue());
    } else if (value instanceof Number) {
      writer.value(((Number) value).doubleValue());
    } else {
      // CharSequence / Character / anything else → string form.
      writer.value(value.toString());
    }
  }

  // ---- test-only accessors ----

  synchronized Set<Integer> serialIdsView() {
    return new TreeSet<>(serialIds);
  }

  synchronized int subjectCount() {
    return subjects.size();
  }

  synchronized int defaultCount() {
    return defaults.size();
  }
}
