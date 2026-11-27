package datadog.trace.instrumentation.rabbitmq.amqp;

/**
 * Normalizes per-instance RabbitMQ queue, routing key and exchange names before they are used in
 * Data Streams Monitoring edge tags.
 *
 * <p>Names generated per process or per request (server-named queues, direct reply-to, bus
 * endpoints, names with a random suffix) would otherwise create a new pathway hash for every
 * instance. Span tags and resource names keep the raw name.
 *
 * <p>The rules are hand-written rather than regex based because they run for every message. Names
 * that don't match any rule are returned as is, without allocating.
 */
public final class RabbitDsmNames {
  static final String AMQ_GEN_PREFIX = "amq.gen-";
  static final String AMQ_GEN_NORMALIZED = "amq.gen-*";
  static final String REPLY_TO_PREFIX = "amq.rabbitmq.reply-to";
  static final String WOLVERINE_PREFIX = "wolverine.";
  static final String BUS_MARKER = "_bus_";
  static final String WILDCARD = "*";

  private static final String[] WOLVERINE_KINDS = {"control", "response", "replies"};
  private static final String[] WOLVERINE_NORMALIZED = {
    "wolverine.control.*", "wolverine.response.*", "wolverine.replies.*"
  };

  /** Length of a dashed UUID: 8-4-4-4-12 hex digits. */
  private static final int UUID_LENGTH = 36;

  /** Length of an undashed UUID: 32 hex digits. */
  private static final int UNDASHED_UUID_LENGTH = 32;

  /** Minimum length of a trailing run of hex digits that is treated as random. */
  private static final int MIN_HEX_SUFFIX = 16;

  private RabbitDsmNames() {}

  /**
   * Returns the name to use in DSM edge tags for a RabbitMQ queue, routing key or exchange.
   *
   * <p>Rules, in order:
   *
   * <ol>
   *   <li>null or empty: unchanged
   *   <li>{@code amq.gen-...}: {@code amq.gen-*}
   *   <li>{@code amq.rabbitmq.reply-to...}: {@code amq.rabbitmq.reply-to}
   *   <li>{@code <a>_<b>_bus_<alnum>} (case-insensitive): {@code *_<b>_bus_*}
   *   <li>{@code wolverine.(control|response|replies).<rest>}: {@code wolverine.<kind>.*}
   *   <li>a separator ({@code -}, {@code _} or {@code .}) followed by a UUID (dashed or 32 hex) or
   *       by 16 or more hex digits at the end: the suffix becomes {@code <sep>*}
   *   <li>the whole name is a UUID (dashed, or exactly 32 hex digits), case-insensitive: {@code *}
   *   <li>otherwise unchanged
   * </ol>
   */
  public static String normalize(final String name) {
    if (name == null || name.isEmpty()) {
      return name;
    }
    if (name.startsWith(AMQ_GEN_PREFIX)) {
      return AMQ_GEN_NORMALIZED;
    }
    if (name.startsWith(REPLY_TO_PREFIX)) {
      return REPLY_TO_PREFIX;
    }
    String normalized = normalizeBusEndpoint(name);
    if (normalized != null) {
      return normalized;
    }
    normalized = normalizeWolverine(name);
    if (normalized != null) {
      return normalized;
    }
    normalized = normalizeRandomSuffix(name);
    if (normalized != null) {
      return normalized;
    }
    if (isBareUuid(name)) {
      return WILDCARD;
    }
    return name;
  }

  /** {@code ^(.+)_(.+)_bus_([a-z0-9]+)$}, case-insensitive, becomes {@code *_<group 2>_bus_*}. */
  private static String normalizeBusEndpoint(final String name) {
    final int length = name.length();
    // The suffix can't contain '_', so only the last "_bus_" can match.
    int marker = -1;
    for (int i = length - BUS_MARKER.length() - 1; i >= 3; i--) {
      if (name.regionMatches(true, i, BUS_MARKER, 0, BUS_MARKER.length())) {
        marker = i;
        break;
      }
    }
    if (marker < 0) {
      return null;
    }
    for (int i = marker + BUS_MARKER.length(); i < length; i++) {
      if (!isAsciiLetterOrDigit(name.charAt(i))) {
        return null;
      }
    }
    // Greedy (.+)_(.+): split the prefix at its last '_' that leaves both groups non-empty.
    for (int i = marker - 2; i >= 1; i--) {
      if (name.charAt(i) == '_') {
        return "*_" + name.substring(i + 1, marker) + "_bus_*";
      }
    }
    return null;
  }

  /**
   * {@code ^wolverine\.(control|response|replies)\.(.+)$} becomes {@code wolverine.<group 1>.*}.
   */
  private static String normalizeWolverine(final String name) {
    if (!name.startsWith(WOLVERINE_PREFIX)) {
      return null;
    }
    final int start = WOLVERINE_PREFIX.length();
    for (int k = 0; k < WOLVERINE_KINDS.length; k++) {
      final String kind = WOLVERINE_KINDS[k];
      final int dot = start + kind.length();
      if (name.length() > dot + 1 && name.startsWith(kind, start) && name.charAt(dot) == '.') {
        return WOLVERINE_NORMALIZED[k];
      }
    }
    return null;
  }

  /**
   * A trailing separator followed by a UUID (dashed, or 32 hex undashed) or by 16 or more hex
   * digits: the suffix becomes {@code <sep>*}.
   */
  private static String normalizeRandomSuffix(final String name) {
    final int length = name.length();
    int hexStart = length;
    while (hexStart > 0 && isHex(name.charAt(hexStart - 1))) {
      hexStart--;
    }
    final int sep;
    if (length - hexStart >= MIN_HEX_SUFFIX) {
      // Also covers 32 hex digit undashed UUIDs.
      sep = hexStart - 1;
    } else if (isDashedUuidAt(name, length - UUID_LENGTH)) {
      sep = length - UUID_LENGTH - 1;
    } else {
      return null;
    }
    if (sep < 0 || !isSeparator(name.charAt(sep))) {
      return null;
    }
    return name.substring(0, sep + 1) + '*';
  }

  /** The whole name is a dashed UUID or exactly 32 hex digits. */
  private static boolean isBareUuid(final String name) {
    final int length = name.length();
    if (length == UUID_LENGTH) {
      return isDashedUuidAt(name, 0);
    }
    if (length != UNDASHED_UUID_LENGTH) {
      return false;
    }
    for (int i = 0; i < length; i++) {
      if (!isHex(name.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static boolean isDashedUuidAt(final String name, final int start) {
    if (start < 0) {
      return false;
    }
    for (int i = 0; i < UUID_LENGTH; i++) {
      final char c = name.charAt(start + i);
      if (i == 8 || i == 13 || i == 18 || i == 23) {
        if (c != '-') {
          return false;
        }
      } else if (!isHex(c)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isSeparator(final char c) {
    return c == '-' || c == '_' || c == '.';
  }

  private static boolean isHex(final char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  private static boolean isAsciiLetterOrDigit(final char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }
}
