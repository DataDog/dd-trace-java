package datadog.trace.api.appsec.route;

import java.nio.charset.StandardCharsets;

/**
 * Percent-encoding rules of RFC-1125: literals and prefixes are encoded as UTF-8 with uppercase
 * hex, keeping only {@code A-Za-z0-9-._~}. A {@code %} is always literal text ({@code %25}), as
 * RFC-1125 requires when the framework's {@code %} handling is unknown.
 */
final class RouteEncoding {

  private static final String HEX = "0123456789ABCDEF";

  private RouteEncoding() {}

  static boolean isUnreserved(char c) {
    return (c >= 'A' && c <= 'Z')
        || (c >= 'a' && c <= 'z')
        || (c >= '0' && c <= '9')
        || c == '-'
        || c == '.'
        || c == '_'
        || c == '~';
  }

  static int hexValue(char c) {
    if (c >= '0' && c <= '9') {
      return c - '0';
    }
    if (c >= 'A' && c <= 'F') {
      return c - 'A' + 10;
    }
    if (c >= 'a' && c <= 'f') {
      return c - 'a' + 10;
    }
    return -1;
  }

  /**
   * Percent-encodes static text: UTF-8, uppercase hex, everything outside {@code A-Za-z0-9-._~}.
   */
  static String encodeLiteral(String text) {
    int i = 0;
    int len = text.length();
    while (i < len && isUnreserved(text.charAt(i))) {
      i++;
    }
    if (i == len) {
      return text;
    }
    StringBuilder sb = new StringBuilder(len + 16);
    sb.append(text, 0, i);
    encodeLiteralTo(text, i, len, sb);
    return sb.toString();
  }

  private static void encodeLiteralTo(String text, int from, int to, StringBuilder sb) {
    for (int i = from; i < to; i++) {
      char c = text.charAt(i);
      if (isUnreserved(c)) {
        sb.append(c);
        continue;
      }
      int cp = c;
      if (Character.isHighSurrogate(c)
          && i + 1 < to
          && Character.isLowSurrogate(text.charAt(i + 1))) {
        cp = Character.toCodePoint(c, text.charAt(i + 1));
        i++;
      } else if (Character.isSurrogate(c)) {
        cp = 0xFFFD; // unpaired surrogate: replacement character
      }
      appendUtf8Escaped(cp, sb);
    }
  }

  private static void appendUtf8Escaped(int cp, StringBuilder sb) {
    if (cp < 0x80) {
      appendEscapedByte(cp, sb);
    } else if (cp < 0x800) {
      appendEscapedByte(0xC0 | (cp >> 6), sb);
      appendEscapedByte(0x80 | (cp & 0x3F), sb);
    } else if (cp < 0x10000) {
      appendEscapedByte(0xE0 | (cp >> 12), sb);
      appendEscapedByte(0x80 | ((cp >> 6) & 0x3F), sb);
      appendEscapedByte(0x80 | (cp & 0x3F), sb);
    } else {
      appendEscapedByte(0xF0 | (cp >> 18), sb);
      appendEscapedByte(0x80 | ((cp >> 12) & 0x3F), sb);
      appendEscapedByte(0x80 | ((cp >> 6) & 0x3F), sb);
      appendEscapedByte(0x80 | (cp & 0x3F), sb);
    }
  }

  private static void appendEscapedByte(int b, StringBuilder sb) {
    sb.append('%').append(HEX.charAt((b >> 4) & 0xF)).append(HEX.charAt(b & 0xF));
  }

  /**
   * Mount prefix as a path without trailing {@code /}: leading {@code /} added, empty segments
   * removed, each segment encoded as a literal. Returns "" for none.
   */
  static String normalizePrefix(String prefix) {
    if (prefix == null || prefix.isEmpty()) {
      return "";
    }
    int len = prefix.length();
    boolean clean = prefix.charAt(0) == '/' && prefix.charAt(len - 1) != '/';
    for (int i = 1; clean && i < len; i++) {
      char c = prefix.charAt(i);
      clean = c == '/' ? prefix.charAt(i - 1) != '/' : isUnreserved(c);
    }
    if (clean) {
      return prefix;
    }
    StringBuilder sb = new StringBuilder(len + 16);
    int start = 0;
    while (start <= len) {
      int end = prefix.indexOf('/', start);
      if (end < 0) {
        end = len;
      }
      if (end > start) {
        sb.append('/');
        encodeLiteralTo(prefix, start, end, sb);
      }
      start = end + 1;
    }
    return sb.toString();
  }

  /**
   * Decodes {@code %XX} escapes of a URL path as UTF-8 ({@code +} stays {@code +}); invalid or
   * truncated escapes are kept as is, malformed UTF-8 becomes U+FFFD. Returns the same instance
   * when there is no {@code %} (including null and empty).
   */
  static String decodePath(String path) {
    int first = path == null ? -1 : path.indexOf('%');
    if (first < 0) {
      return path;
    }
    int len = path.length();
    StringBuilder sb = new StringBuilder(len);
    sb.append(path, 0, first);
    byte[] bytes = null;
    int i = first;
    while (i < len) {
      if (!isEscape(path, i)) {
        sb.append(path.charAt(i++));
        continue;
      }
      if (bytes == null) {
        bytes = new byte[(len - i) / 3]; // enough for every escape left
      }
      int n = 0;
      do {
        bytes[n++] = (byte) ((hexValue(path.charAt(i + 1)) << 4) | hexValue(path.charAt(i + 2)));
        i += 3;
      } while (i < len && isEscape(path, i));
      sb.append(new String(bytes, 0, n, StandardCharsets.UTF_8));
    }
    return sb.toString();
  }

  private static boolean isEscape(String s, int i) {
    return s.charAt(i) == '%'
        && i + 2 < s.length()
        && hexValue(s.charAt(i + 1)) >= 0
        && hexValue(s.charAt(i + 2)) >= 0;
  }
}
