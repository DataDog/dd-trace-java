package datadog.openfeature.internal.telemetry;

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
 * <p>This class has no tracer or Java agent dependency. An attached-agent adapter can associate an
 * instance with a local-root span. A future agentless adapter can use the same state and encoding.
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
public final class SpanEnrichmentAccumulator {

  public static final int MAX_SERIAL_IDS = 200;
  public static final int MAX_SUBJECTS = 10;
  public static final int MAX_EXPERIMENTS_PER_SUBJECT = 20;
  public static final int MAX_DEFAULTS = 5;
  public static final int MAX_DEFAULT_VALUE_LENGTH = 64;

  public static final String TAG_FLAGS_ENC = "ffe_flags_enc";
  public static final String TAG_SUBJECTS_ENC = "ffe_subjects_enc";
  public static final String TAG_RUNTIME_DEFAULTS = "ffe_runtime_defaults";

  // dedupe is structural (a Set); sorted for deterministic encoding.
  private final TreeSet<Integer> serialIds = new TreeSet<>();
  // sha256hex(targetingKey) -> serial ids. LinkedHashMap for stable iteration order.
  private final Map<String, TreeSet<Integer>> subjects = new LinkedHashMap<>();
  // flagKey -> value string (first-wins, truncated to MAX_DEFAULT_VALUE_LENGTH).
  private final Map<String, String> defaults = new LinkedHashMap<>();

  /** Adds a serial id, dropping silently once {@link #MAX_SERIAL_IDS} is reached. */
  public synchronized void addSerialId(final int id) {
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
  public synchronized void addSubject(final String targetingKey, final int id) {
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
  public synchronized void addDefault(final String flagKey, final Object value) {
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
  public synchronized boolean hasData() {
    return !serialIds.isEmpty() || !defaults.isEmpty();
  }

  /**
   * Builds the {@code ffe_*} span tags from the accumulated state. Empty groups are omitted.
   *
   * @return a map of tag name to tag value (a subset of {@code ffe_flags_enc}, {@code
   *     ffe_subjects_enc}, {@code ffe_runtime_defaults})
   */
  public synchronized Map<String, String> toSpanTags() {
    final Map<String, String> tags = new LinkedHashMap<>();
    if (!serialIds.isEmpty()) {
      tags.put(TAG_FLAGS_ENC, ULeb128Encoder.encodeDeltaVarint(serialIds));
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
   * Serializes a String-&gt;String map to a compact JSON object string.
   *
   * <p>The local encoder keeps this module independent from agent and provider JSON libraries.
   */
  static String toJsonObject(final Map<String, String> map) {
    final StringBuilder json = new StringBuilder();
    json.append('{');
    boolean first = true;
    for (final Map.Entry<String, String> entry : map.entrySet()) {
      if (!first) {
        json.append(',');
      }
      first = false;
      appendJsonString(json, entry.getKey());
      json.append(':');
      appendJsonString(json, entry.getValue());
    }
    return json.append('}').toString();
  }

  private static String toJsonValue(final Object value) {
    final StringBuilder json = new StringBuilder();
    appendJsonValue(json, value);
    return json.toString();
  }

  @SuppressWarnings("unchecked")
  private static void appendJsonValue(final StringBuilder json, final Object value) {
    // Callers pass values already unwrapped to native form by the capture side, so no OpenFeature
    // Value ever reaches here.
    if (value == null) {
      json.append("null");
    } else if (value instanceof Map) {
      json.append('{');
      boolean first = true;
      for (final Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        if (!first) {
          json.append(',');
        }
        first = false;
        appendJsonString(json, String.valueOf(entry.getKey()));
        json.append(':');
        appendJsonValue(json, entry.getValue());
      }
      json.append('}');
    } else if (value instanceof Iterable) {
      json.append('[');
      boolean first = true;
      for (final Object element : (Iterable<Object>) value) {
        if (!first) {
          json.append(',');
        }
        first = false;
        appendJsonValue(json, element);
      }
      json.append(']');
    } else if (value instanceof Boolean) {
      json.append(value);
    } else if (value instanceof Integer
        || value instanceof Long
        || value instanceof Short
        || value instanceof Byte) {
      json.append(((Number) value).longValue());
    } else if (value instanceof Number) {
      final double number = ((Number) value).doubleValue();
      if (Double.isNaN(number)) {
        json.append("null");
      } else {
        json.append(number);
      }
    } else {
      // CharSequence / Character / anything else → string form.
      appendJsonString(json, value.toString());
    }
  }

  private static void appendJsonString(final StringBuilder json, final String value) {
    json.append('"');
    for (int index = 0; index < value.length(); index++) {
      final char character = value.charAt(index);
      if (character > 127) {
        appendUnicodeEscape(json, character);
        continue;
      }
      switch (character) {
        case '"':
        case '\\':
        case '/':
          json.append('\\').append(character);
          break;
        case '\b':
          json.append("\\b");
          break;
        case '\f':
          json.append("\\f");
          break;
        case '\n':
          json.append("\\n");
          break;
        case '\r':
          json.append("\\r");
          break;
        case '\t':
          json.append("\\t");
          break;
        default:
          if (character < 0x20) {
            appendUnicodeEscape(json, character);
          } else {
            json.append(character);
          }
          break;
      }
    }
    json.append('"');
  }

  private static void appendUnicodeEscape(final StringBuilder json, final char character) {
    json.append("\\u")
        .append(HEX_DIGITS[(character >>> 12) & 0xF])
        .append(HEX_DIGITS[(character >>> 8) & 0xF])
        .append(HEX_DIGITS[(character >>> 4) & 0xF])
        .append(HEX_DIGITS[character & 0xF]);
  }

  private static final char[] HEX_DIGITS = {
    '0', '1', '2', '3', '4', '5', '6', '7',
    '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
  };

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
