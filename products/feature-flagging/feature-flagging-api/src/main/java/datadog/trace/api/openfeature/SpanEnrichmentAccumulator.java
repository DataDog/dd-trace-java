package datadog.trace.api.openfeature;

import dev.openfeature.sdk.Structure;
import dev.openfeature.sdk.Value;
import java.time.Instant;
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
 * local-root span's full trace id. The capture hook ({@link SpanEnrichmentHook}) writes; the write
 * interceptor ({@link SpanEnrichmentInterceptor}) reads and clears. When the span-enrichment gate
 * is off, no store and no accumulator are ever created, so there is no idle per-span overhead.
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
   * Records a runtime-default value for {@code flagKey} (first-wins). Object values are serialized
   * to JSON (NOT {@code toString()}); the result is truncated to {@link #MAX_DEFAULT_VALUE_LENGTH}.
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
   * String(value)} rule: structured values (objects, arrays) are JSON-stringified; scalars use
   * their string form; {@code null} becomes the bare {@code null}.
   *
   * <p>On the real OpenFeature object-evaluation path the runtime-default arrives wrapped in a
   * {@link Value}; we unwrap it to its native representation first so a structured default
   * serializes to JSON (matching Node's {@code JSON.stringify} of the equivalent JS object) instead
   * of {@code Value.toString()} (which is {@code "Value(innerObject=...)"}). The scalar cases
   * ({@code String}/{@code Boolean}/number) collapse to the same string form Node produces.
   */
  static String stringifyDefault(final Object value) {
    final Object unwrapped = value instanceof Value ? unwrapValue((Value) value) : value;
    if (unwrapped == null) {
      return "null";
    }
    if (unwrapped instanceof Map
        || unwrapped instanceof Iterable
        || unwrapped.getClass().isArray()) {
      return toJsonValue(unwrapped);
    }
    if (unwrapped instanceof CharSequence || unwrapped instanceof Character) {
      return unwrapped.toString();
    }
    // Numbers / booleans / Instant — their string form matches what Node's String(value) emits for
    // these scalar cases.
    return String.valueOf(unwrapped);
  }

  /**
   * Recursively unwraps an OpenFeature {@link Value} into its native Java representation:
   * structures become {@code Map<String, Object>}, lists become {@code List<Object>}, and scalars
   * become their boxed value (or {@code null}). Nested {@link Value}s are unwrapped at every level
   * so a structure containing further structures/lists serializes correctly.
   */
  private static Object unwrapValue(final Value value) {
    if (value == null || value.isNull()) {
      return null;
    }
    if (value.isStructure()) {
      final Structure structure = value.asStructure();
      final Map<String, Object> map = new LinkedHashMap<>();
      if (structure != null) {
        for (final String key : structure.keySet()) {
          map.put(key, unwrapValue(structure.getValue(key)));
        }
      }
      return map;
    }
    if (value.isList()) {
      final java.util.List<Value> list = value.asList();
      final java.util.List<Object> out = new java.util.ArrayList<>(list == null ? 0 : list.size());
      if (list != null) {
        for (final Value element : list) {
          out.add(unwrapValue(element));
        }
      }
      return out;
    }
    if (value.isBoolean()) {
      return value.asBoolean();
    }
    if (value.isString()) {
      return value.asString();
    }
    if (value.isNumber()) {
      // Preserve integral vs fractional so the rendered JSON number matches Node.
      final Double d = value.asDouble();
      if (d != null && d == Math.rint(d) && !Double.isInfinite(d)) {
        final Integer i = value.asInteger();
        if (i != null) {
          return i;
        }
      }
      return d;
    }
    final Instant instant = value.asInstant();
    if (instant != null) {
      return instant.toString();
    }
    // Unknown shape: fall back to the wrapped object's own representation.
    return value.asObject();
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

  /** Serializes a String->String map to a compact JSON object string (keys + values escaped). */
  static String toJsonObject(final Map<String, String> map) {
    final StringBuilder sb = new StringBuilder();
    sb.append('{');
    boolean first = true;
    for (final Map.Entry<String, String> entry : map.entrySet()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      appendJsonString(sb, entry.getKey());
      sb.append(':');
      appendJsonString(sb, entry.getValue());
    }
    sb.append('}');
    return sb.toString();
  }

  private static String toJsonValue(final Object value) {
    final StringBuilder sb = new StringBuilder();
    appendJsonValue(sb, value);
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  private static void appendJsonValue(final StringBuilder sb, final Object value) {
    // Callers pass values already unwrapped to native form by stringifyDefault/unwrapValue, so no
    // OpenFeature Value ever reaches here.
    if (value == null) {
      sb.append("null");
    } else if (value instanceof Map) {
      sb.append('{');
      boolean first = true;
      for (final Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        appendJsonString(sb, String.valueOf(entry.getKey()));
        sb.append(':');
        appendJsonValue(sb, entry.getValue());
      }
      sb.append('}');
    } else if (value instanceof Iterable) {
      sb.append('[');
      boolean first = true;
      for (final Object element : (Iterable<Object>) value) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        appendJsonValue(sb, element);
      }
      sb.append(']');
    } else if (value instanceof CharSequence || value instanceof Character) {
      appendJsonString(sb, value.toString());
    } else if (value instanceof Number || value instanceof Boolean) {
      sb.append(String.valueOf(value));
    } else {
      appendJsonString(sb, String.valueOf(value));
    }
  }

  private static void appendJsonString(final StringBuilder sb, final String value) {
    sb.append('"');
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      switch (c) {
        case '"':
          sb.append("\\\"");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        case '\b':
          sb.append("\\b");
          break;
        case '\f':
          sb.append("\\f");
          break;
        default:
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
      }
    }
    sb.append('"');
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
