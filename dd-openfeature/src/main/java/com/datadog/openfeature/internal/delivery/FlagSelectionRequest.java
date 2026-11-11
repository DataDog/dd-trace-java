package com.datadog.openfeature.internal.delivery;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;

import com.datadog.openfeature.internal.RuntimeServices;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The flag selection sent with the agentless configuration request, canonicalized and validated
 * like every other Datadog SDK does, or the reason it is not sent. Its diagnostics are reported
 * once per class loader, like the runtime shared by the providers.
 */
public final class FlagSelectionRequest {
  private static final Logger LOGGER = LoggerFactory.getLogger(FlagSelectionRequest.class);

  // The endpoint limits, shared by every SDK:
  // https://github.com/DataDog/ffe-system-test-data/blob/main/flag-selection/README.md
  static final int MAX_VALUES = 31;
  static final int MAX_VALUE_BYTES = 200;
  // Fastly answers URLs over 8 KB with 414 URI Too Long:
  // https://docs.fastly.com/products/network-services-resource-limits
  static final int MAX_URL_BYTES = 8192;

  static final String IGNORED_HEADER = "DD-Flag-Selection-Ignored";
  static final String DROPPED_METRIC = "flag_selection.dropped";
  static final String IGNORED_METRIC = "flag_selection.ignored";

  private static final String MALFORMED_SELECTION = "malformed_selection";
  private static final String TOO_MANY_VALUES = "too_many_values";
  private static final String INVALID_KEY_PATTERN = "invalid_key_pattern";
  private static final String INVALID_TAG = "invalid_tag";
  private static final String UNKNOWN_REASON = "unknown";
  private static final Set<String> IGNORED_REASONS =
      Set.of(
          MALFORMED_SELECTION,
          TOO_MANY_VALUES,
          INVALID_KEY_PATTERN,
          INVALID_TAG,
          "tag_load_failed");

  private static final String MALFORMED_LIMIT =
      "flagSelection must be an object with optional tags and keyPatterns string lists";
  private static final String VALUES_LIMIT = "each list accepts at most " + MAX_VALUES + " values";
  private static final String KEY_PATTERN_LIMIT =
      "each key pattern is at most " + MAX_VALUE_BYTES + " UTF-8 bytes";
  private static final String TAG_LIMIT = "each tag is at most " + MAX_VALUE_BYTES + " UTF-8 bytes";
  private static final String URL_LIMIT = "the request URL is at most " + MAX_URL_BYTES + " bytes";

  private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
  private static final Object[] NO_VALUES = new Object[0];
  private static final char[] HEX = "0123456789ABCDEF".toCharArray();

  /** No selection: all the flags are loaded. */
  public static final FlagSelectionRequest NONE = new FlagSelectionRequest(null, null);

  private static final FlagSelectionRequest URL_TOO_LONG =
      new FlagSelectionRequest(TOO_MANY_VALUES, URL_LIMIT);

  private final List<String> tags;
  private final List<String> keyPatterns;
  // The UTF-8 encodings of the values, computed once for sorting and reused for the request URL.
  private final List<byte[]> utf8Tags;
  private final List<byte[]> utf8KeyPatterns;
  @Nullable private final String dropReason;
  @Nullable private final String dropLimit;

  private FlagSelectionRequest(
      @Nullable final String dropReason, @Nullable final String dropLimit) {
    this(emptyList(), emptyList(), dropReason, dropLimit);
  }

  private FlagSelectionRequest(
      final List<Map.Entry<String, byte[]>> tags,
      final List<Map.Entry<String, byte[]>> keyPatterns,
      @Nullable final String dropReason,
      @Nullable final String dropLimit) {
    this.tags = keys(tags);
    this.keyPatterns = keys(keyPatterns);
    this.utf8Tags = values(tags);
    this.utf8KeyPatterns = values(keyPatterns);
    this.dropReason = dropReason;
    this.dropLimit = dropLimit;
  }

  /**
   * Canonicalizes and validates a selection. Never throws: an element that is not a string, or a
   * string with an unpaired surrogate, is reported as {@code malformed_selection}.
   *
   * @param tags the tags, or {@code null} for none.
   * @param keyPatterns the key patterns, or {@code null} for none.
   * @return the canonical selection, {@link #NONE}, or the reason it is not sent.
   */
  public static FlagSelectionRequest of(
      @Nullable final List<?> tags, @Nullable final List<?> keyPatterns) {
    final Object[] rawTags;
    final Object[] rawKeyPatterns;
    try {
      rawTags = tags == null ? NO_VALUES : Objects.requireNonNull(tags.toArray());
      rawKeyPatterns =
          keyPatterns == null ? NO_VALUES : Objects.requireNonNull(keyPatterns.toArray());
    } catch (final RuntimeException e) {
      // For example a list that the application modifies concurrently, or a toArray() that breaks
      // its contract by returning null.
      return dropped(MALFORMED_SELECTION, MALFORMED_LIMIT);
    }
    if (!areWellFormedStrings(rawTags) || !areWellFormedStrings(rawKeyPatterns)) {
      return dropped(MALFORMED_SELECTION, MALFORMED_LIMIT);
    }
    final List<Map.Entry<String, byte[]>> canonicalTags = canonicalize(rawTags);
    final List<Map.Entry<String, byte[]>> canonicalKeyPatterns = canonicalize(rawKeyPatterns);
    if (canonicalTags.size() > MAX_VALUES || canonicalKeyPatterns.size() > MAX_VALUES) {
      return dropped(TOO_MANY_VALUES, VALUES_LIMIT);
    }
    if (anyOverLimit(canonicalKeyPatterns)) {
      return dropped(INVALID_KEY_PATTERN, KEY_PATTERN_LIMIT);
    }
    if (anyOverLimit(canonicalTags)) {
      return dropped(INVALID_TAG, TAG_LIMIT);
    }
    if (canonicalTags.isEmpty() && canonicalKeyPatterns.isEmpty()) {
      return NONE;
    }
    return new FlagSelectionRequest(canonicalTags, canonicalKeyPatterns, null, null);
  }

  /**
   * @return the selection that is not sent because the request URL would be over the limit.
   */
  static FlagSelectionRequest urlTooLong() {
    return URL_TOO_LONG;
  }

  private static FlagSelectionRequest dropped(final String reason, final String limit) {
    return new FlagSelectionRequest(reason, limit);
  }

  private static boolean areWellFormedStrings(final Object[] values) {
    for (final Object value : values) {
      if (!(value instanceof String) || hasUnpairedSurrogate((String) value)) {
        return false;
      }
    }
    return true;
  }

  // String.getBytes(UTF_8) replaces an unpaired surrogate with '?', so check before encoding.
  private static boolean hasUnpairedSurrogate(final String value) {
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      if (Character.isHighSurrogate(c)
          && i + 1 < value.length()
          && Character.isLowSurrogate(value.charAt(i + 1))) {
        i++;
      } else if (Character.isSurrogate(c)) {
        return true;
      }
    }
    return false;
  }

  private static List<Map.Entry<String, byte[]>> canonicalize(final Object[] values) {
    final Map<String, byte[]> unique = new LinkedHashMap<>();
    for (final Object value : values) {
      final String trimmed = trimWhiteSpace((String) value);
      if (!trimmed.isEmpty()) {
        unique.computeIfAbsent(trimmed, v -> v.getBytes(UTF_8));
      }
    }
    final List<Map.Entry<String, byte[]>> entries = new ArrayList<>(unique.entrySet());
    // UTF-8 byte order, which differs from String.compareTo outside the Basic Multilingual Plane.
    entries.sort((left, right) -> Arrays.compareUnsigned(left.getValue(), right.getValue()));
    return entries;
  }

  private static List<String> keys(final List<Map.Entry<String, byte[]>> entries) {
    final List<String> keys = new ArrayList<>(entries.size());
    for (final Map.Entry<String, byte[]> entry : entries) {
      keys.add(entry.getKey());
    }
    return unmodifiableList(keys);
  }

  private static List<byte[]> values(final List<Map.Entry<String, byte[]>> entries) {
    final List<byte[]> values = new ArrayList<>(entries.size());
    for (final Map.Entry<String, byte[]> entry : entries) {
      values.add(entry.getValue());
    }
    return unmodifiableList(values);
  }

  private static String trimWhiteSpace(final String value) {
    int start = 0;
    int end = value.length();
    while (start < end && isWhiteSpace(value.charAt(start))) {
      start++;
    }
    while (end > start && isWhiteSpace(value.charAt(end - 1))) {
      end--;
    }
    return value.substring(start, end);
  }

  // The Unicode White_Space property, which String.trim() and String.strip() both differ from:
  // https://www.unicode.org/Public/UCD/latest/ucd/PropList.txt
  private static boolean isWhiteSpace(final char c) {
    return (c >= '\t' && c <= '\r')
        || c == ' '
        || c == '\u0085'
        || c == '\u00A0'
        || c == '\u1680'
        || (c >= '\u2000' && c <= '\u200A')
        || c == '\u2028'
        || c == '\u2029'
        || c == '\u202F'
        || c == '\u205F'
        || c == '\u3000';
  }

  private static boolean anyOverLimit(final List<Map.Entry<String, byte[]>> entries) {
    for (final Map.Entry<String, byte[]> entry : entries) {
      if (entry.getValue().length > MAX_VALUE_BYTES) {
        return true;
      }
    }
    return false;
  }

  /**
   * @return whether there is no selection: nothing to send, and nothing dropped.
   */
  public boolean isNone() {
    return this.dropReason == null && !hasValues();
  }

  boolean hasValues() {
    return !this.tags.isEmpty() || !this.keyPatterns.isEmpty();
  }

  List<String> tags() {
    return this.tags;
  }

  List<String> keyPatterns() {
    return this.keyPatterns;
  }

  @Nullable
  String dropReason() {
    return this.dropReason;
  }

  /**
   * Appends the selection to the query of the endpoint, as repeated {@code selected_flag_tags} and
   * {@code selected_flag_key_patterns} parameters, before any fragment.
   *
   * @param endpoint the configuration endpoint.
   * @return the endpoint with the selection, the endpoint itself when there is nothing to send, or
   *     {@code null} when the request URL would be over {@link #MAX_URL_BYTES}.
   */
  @Nullable
  URI appendTo(final URI endpoint) {
    if (!hasValues()) {
      return endpoint;
    }
    final StringBuilder url =
        new StringBuilder()
            .append(endpoint.getScheme())
            .append("://")
            .append(endpoint.getRawAuthority())
            .append(endpoint.getRawPath())
            .append('?');
    final String query = endpoint.getRawQuery();
    boolean first = query == null || query.isEmpty();
    if (!first) {
      url.append(query);
    }
    for (final byte[] tag : this.utf8Tags) {
      appendParameter(url, first, "selected_flag_tags", tag);
      first = false;
    }
    for (final byte[] keyPattern : this.utf8KeyPatterns) {
      appendParameter(url, first, "selected_flag_key_patterns", keyPattern);
      first = false;
    }
    final String fragment = endpoint.getRawFragment();
    if (fragment != null) {
      url.append('#').append(fragment);
    }
    final URI selected = URI.create(url.toString());
    // Every character is ASCII once percent-encoded, so the length is the byte count.
    return selected.toASCIIString().length() > MAX_URL_BYTES ? null : selected;
  }

  private static void appendParameter(
      final StringBuilder url, final boolean first, final String name, final byte[] utf8Value) {
    if (!first) {
      url.append('&');
    }
    url.append(name).append('=');
    // URLEncoder is form encoding: it sends a space as '+' and keeps '*'.
    for (final byte b : utf8Value) {
      final int octet = b & 0xFF;
      if (isUnreserved(octet)) {
        url.append((char) octet);
      } else {
        url.append('%').append(HEX[octet >> 4]).append(HEX[octet & 0xF]);
      }
    }
  }

  // RFC 3986 unreserved characters: https://www.rfc-editor.org/rfc/rfc3986#section-2.3
  private static boolean isUnreserved(final int octet) {
    return (octet >= 'A' && octet <= 'Z')
        || (octet >= 'a' && octet <= 'z')
        || (octet >= '0' && octet <= '9')
        || octet == '-'
        || octet == '.'
        || octet == '_'
        || octet == '~';
  }

  /**
   * Reports a selection that is not sent: logs it at each call, once per provider initialization,
   * and counts it once per reason.
   *
   * @param services the runtime services counting the drop.
   */
  public void reportNotSent(final RuntimeServices services) {
    final String reason = this.dropReason;
    if (reason == null) {
      return;
    }
    if (REPORTED.add("dropped:" + reason)) {
      count(services, DROPPED_METRIC, reason);
    }
    error(
        "flagSelection was not sent ({}: {}). Loading the full flag configuration.",
        reason,
        this.dropLimit);
  }

  /**
   * Sanitizes the {@code DD-Flag-Selection-Ignored} response header, so that only a known reason
   * reaches the log and the metric.
   *
   * @param header the header value, or {@code null} if absent.
   * @return the known reason, {@code unknown} for any other value, or {@code null} if absent or
   *     empty.
   */
  @Nullable
  static String ignoredReason(@Nullable final String header) {
    if (header == null || header.isEmpty()) {
      return null;
    }
    // Every known reason is ASCII and under the 32-byte cap, so an exact match enforces the cap.
    return IGNORED_REASONS.contains(header) ? header : UNKNOWN_REASON;
  }

  /**
   * Reports a selection that the server ignored, once per reason.
   *
   * @param services the runtime services counting it.
   * @param reason the sanitized reason.
   */
  static void reportIgnored(final RuntimeServices services, final String reason) {
    if (REPORTED.add("ignored:" + reason)) {
      count(services, IGNORED_METRIC, reason);
      error(
          "flagSelection was ignored by the server ({}). Loaded the full flag configuration.",
          reason);
    }
  }

  /** Warns, once, that a provider's selection differs from the one already in use. */
  public static void warnSecondSelection() {
    if (REPORTED.add("second_selection")) {
      warn(
          "flagSelection differs from the flag selection already in use in this process. Using"
              + " the first selection.");
    }
  }

  /** Warns, once, that Remote Configuration delivery does not support a selection. */
  public static void warnRemoteConfiguration() {
    if (REPORTED.add("remote_configuration")) {
      warn(
          "flagSelection is not supported when flags are delivered through Remote Config. Loading"
              + " the full flag configuration.");
    }
  }

  static void resetReportedForTest() {
    REPORTED.clear();
  }

  // A failing metrics or logging backend must not fail the provider.
  private static void count(
      final RuntimeServices services, final String metric, final String reason) {
    try {
      services.countMetric(metric, 1, reason);
    } catch (final RuntimeException | LinkageError ignored) {
      // Nothing more can be done.
    }
  }

  private static void error(final String message, final Object... arguments) {
    try {
      LOGGER.error(message, arguments);
    } catch (final RuntimeException | LinkageError ignored) {
      // Nothing more can be done.
    }
  }

  private static void warn(final String message) {
    try {
      LOGGER.warn(message);
    } catch (final RuntimeException | LinkageError ignored) {
      // Nothing more can be done.
    }
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof FlagSelectionRequest)) {
      return false;
    }
    final FlagSelectionRequest that = (FlagSelectionRequest) other;
    return this.tags.equals(that.tags)
        && this.keyPatterns.equals(that.keyPatterns)
        && Objects.equals(this.dropReason, that.dropReason)
        && Objects.equals(this.dropLimit, that.dropLimit);
  }

  @Override
  public int hashCode() {
    int hash = this.tags.hashCode();
    hash = 31 * hash + this.keyPatterns.hashCode();
    hash = 31 * hash + Objects.hashCode(this.dropReason);
    return 31 * hash + Objects.hashCode(this.dropLimit);
  }
}
