package datadog.trace.bootstrap.instrumentation.api;

import static datadog.trace.api.telemetry.LogCollector.EXCLUDE_TELEMETRY;

import datadog.trace.api.iast.util.PropagationUtils;
import datadog.trace.util.AdaptiveLatch;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class URIUtils {
  private URIUtils() {}

  // This is the � character, which is also the default replacement for the UTF_8 charset
  private static final byte[] REPLACEMENT = {(byte) 0xEF, (byte) 0xBF, (byte) 0xBD};

  private static final Logger LOGGER = LoggerFactory.getLogger(URIUtils.class);

  /**
   * Decodes a %-encoded UTF-8 {@code String} into a regular {@code String}.
   *
   * <p>All illegal % sequences and illegal UTF-8 sequences are replaced with one or more �
   * characters.
   */
  public static String decode(String encoded) {
    return decode(encoded, false);
  }

  /**
   * Decodes a %-encoded UTF-8 {@code String} into a regular {@code String}. Can also be made to
   * decode '+' to ' ' to support old query strings.
   *
   * <p>All illegal % sequences and illegal UTF-8 sequences are replaced with one or more �
   * characters.
   */
  public static String decode(String encoded, boolean plusToSpace) {
    if (encoded == null) return null;
    int len = encoded.length();
    if (len == 0) return encoded;
    if (encoded.indexOf('%') < 0 && (!plusToSpace || encoded.indexOf('+') < 0)) return encoded;

    ByteBuffer bb =
        ByteBuffer.allocate(len + 2); // The extra 2 is if we have a % last and need to replace it
    for (int i = 0; i < len; i++) {
      int c = encoded.charAt(i);
      if (c == '%') {
        if (i + 2 < len) {
          int h = Character.digit(encoded.charAt(i + 1), 16);
          int l = Character.digit(encoded.charAt(i + 2), 16);
          if ((h | l) < 0) {
            bb.put(REPLACEMENT[0]);
            bb.put(REPLACEMENT[1]);
            bb.put(REPLACEMENT[2]);
          } else {
            bb.put((byte) ((h << 4) + l));
          }
          i += 2;
        } else {
          bb.put(REPLACEMENT[0]);
          bb.put(REPLACEMENT[1]);
          bb.put(REPLACEMENT[2]);
          i = len;
        }
      } else {
        if (plusToSpace && c == '+') {
          c = ' ';
        }
        bb.put((byte) c);
      }
    }
    bb.flip();
    return new String(bb.array(), 0, bb.limit(), StandardCharsets.UTF_8);
  }

  /**
   * Build a URL based on the scheme, host, port and path.
   *
   * <p>Will remove the port if it is <= 0 or if its the default http/https port.
   */
  public static String buildURL(String scheme, String host, int port, String path) {
    int length = 0;
    length += null == scheme ? 0 : scheme.length() + 3;
    if (null != host) {
      length += host.length();
      if (port > 0 && port != 80 && port != 443) {
        length += 6;
      }
    }
    if (null == path || path.isEmpty()) {
      ++length;
    } else {
      if (path.charAt(0) != '/') {
        ++length;
      }
      length += path.length();
    }
    final StringBuilder urlNoParams = new StringBuilder(length);
    if (scheme != null) {
      urlNoParams.append(scheme);
      urlNoParams.append("://");
    }

    if (host != null) {
      urlNoParams.append(host);
      if (port > 0
          && !(port == 80 && "http".equals(scheme) || port == 443 && "https".equals(scheme))) {
        urlNoParams.append(':');
        urlNoParams.append(port);
      }
    }

    if (null == path || path.isEmpty()) {
      urlNoParams.append('/');
    } else {
      if (path.charAt(0) != '/' && urlNoParams.length() > 0) {
        urlNoParams.append('/');
      }
      urlNoParams.append(path);
    }
    return urlNoParams.toString();
  }

  /**
   * Parses the given string as a {@link URI} without throwing.
   *
   * @param unparsed The string to parse
   * @return The parsed {@code URI}, or {@code null} if {@code unparsed} is {@code null} or is not a
   *     valid URI
   */
  @Nullable
  public static URI safeParse(@Nullable final String unparsed) {
    if (unparsed == null) {
      return null;
    }
    try {
      return PropagationUtils.onUriCreate(unparsed, URI.create(unparsed));
    } catch (final IllegalArgumentException exception) {
      LOGGER.debug(EXCLUDE_TELEMETRY, "Unable to parse request uri {}", unparsed, exception);
      return null;
    }
  }

  /**
   * Converts a {@link URL} to a {@link URI} like {@link URL#toURI()}, but first percent-encodes the
   * characters that {@link URI} rejects even though {@link URL} accepts them, so that a common kind
   * of bad input does not cost a thrown and caught {@link URISyntaxException} on every request.
   *
   * <p>These are: a space or control character, any of {@code " < > \ ^ ` { | }}, a {@code [} or
   * {@code ]} in the path, a {@code %} that is not followed by two hex digits, and a second {@code
   * #}. Well-formed URLs are converted exactly as {@code url.toURI()} would, with no extra
   * allocation. Other problems, such as an invalid scheme, still throw {@link URISyntaxException}.
   *
   * <p>Bad URLs tend to come from the same application code again and again, so the repair runs
   * only while they are arriving (see {@link ToURI}); otherwise this is just {@code url.toURI()}.
   */
  public static URI toURI(final URL url) throws URISyntaxException {
    final URI uri = TO_URI.tryApply(url);
    // null only when the repair could not help: let URI report why
    return uri != null ? uri : url.toURI();
  }

  private static final ToURI TO_URI = new ToURI();

  /**
   * Chooses between {@code url.toURI()} and the repairing conversion: strict while URLs are
   * well-formed, so they skip the scan; repairing once a bad one arrives, until {@link
   * #CLOSE_AFTER} well-formed ones in a row.
   */
  static final class ToURI extends AdaptiveLatch<URL, URI, URISyntaxException> {
    /**
     * The rent-or-buy break-even (see {@link AdaptiveLatch}): a thrown and caught {@link
     * URISyntaxException} under an HTTP client's stack, against one extra scan of a well-formed
     * URL. An estimate, not yet measured.
     */
    static final int CLOSE_AFTER = 64;

    ToURI() {
      super(URISyntaxException.class, CLOSE_AFTER);
    }

    @Override
    protected URI apply(final URL url) throws URISyntaxException {
      return url.toURI();
    }

    @Override
    protected URI applySafely(final URL url) {
      final String s = url.toString();
      final String escaped = escapeIllegalURIChars(s);
      try {
        return escaped == null ? new URI(s) : repaired(new URI(escaped));
      } catch (URISyntaxException e) {
        return reject(url);
      }
    }
  }

  /** Percent-encodes the characters {@link URI} rejects; returns {@code null} if there are none. */
  @Nullable
  private static String escapeIllegalURIChars(final String s) {
    final int length = s.length();
    final int pathStart = pathStart(s);
    int pathEnd = length;
    for (int i = pathStart; i < length; i++) {
      final char c = s.charAt(i);
      if (c == '?' || c == '#') {
        pathEnd = i;
        break;
      }
    }

    StringBuilder escaped = null;
    boolean inFragment = false;
    for (int i = 0; i < length; i++) {
      final char c = s.charAt(i);
      final boolean illegal;
      if (c == '%') {
        illegal = !isPercentEscape(s, i);
      } else if (c == '#') {
        illegal = inFragment; // only the first '#' starts the fragment
        inFragment = true;
      } else if (c == '[' || c == ']') {
        illegal = i >= pathStart && i < pathEnd; // allowed in an IPv6 host, the query and fragment
      } else {
        illegal = isIllegalURIChar(c);
      }
      if (illegal) {
        if (escaped == null) {
          escaped = new StringBuilder(length + 16).append(s, 0, i);
        }
        appendPercentEncoded(escaped, c);
      } else if (escaped != null) {
        escaped.append(c);
      }
    }
    return escaped == null ? null : escaped.toString();
  }

  /** Index where the path starts: after the authority of {@code scheme://authority}, or 0. */
  private static int pathStart(final String s) {
    final int schemeEnd = s.indexOf("://");
    if (schemeEnd < 0) {
      return 0;
    }
    for (int i = schemeEnd + 3; i < s.length(); i++) {
      final char c = s.charAt(i);
      if (c == '/' || c == '?' || c == '#') {
        return i;
      }
    }
    return s.length();
  }

  private static boolean isPercentEscape(final String s, final int i) {
    return i + 2 < s.length() && isHexDigit(s.charAt(i + 1)) && isHexDigit(s.charAt(i + 2));
  }

  private static boolean isHexDigit(final char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  private static boolean isIllegalURIChar(final char c) {
    if (c <= ' ' || c == 0x7F) {
      return true;
    }
    switch (c) {
      case '"':
      case '<':
      case '>':
      case '\\':
      case '^':
      case '`':
      case '{':
      case '|':
      case '}':
        return true;
      default:
        // URI accepts other non-ASCII characters, but not spaces or control characters
        return c >= 0x80 && (Character.isSpaceChar(c) || Character.isISOControl(c));
    }
  }

  private static void appendPercentEncoded(final StringBuilder sb, final char c) {
    if (c < 0x80) {
      appendPercentEncoded(sb, (byte) c);
    } else {
      for (final byte b : String.valueOf(c).getBytes(StandardCharsets.UTF_8)) {
        appendPercentEncoded(sb, b);
      }
    }
  }

  private static void appendPercentEncoded(final StringBuilder sb, final byte b) {
    sb.append('%')
        .append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
        .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
  }

  /**
   * Builds a lazily evaluated valid URL based on the scheme, host, port and path.
   *
   * <p>Will remove the port if it is <= 0 or if its the default http/https port.
   *
   * @param scheme The scheme
   * @param host The host
   * @param port The port
   * @param path The path
   * @return The {@code LazyUrl}
   */
  public static LazyUrl lazyValidURL(String scheme, String host, int port, String path) {
    return new ValidUrl(scheme, host, port, path);
  }

  /**
   * Builds an invalid URL from a raw string representation.
   *
   * @param raw The raw {@code String} representation of the invalid URL
   * @return The {@code LazyUrl}
   */
  public static LazyUrl lazyInvalidUrl(String raw) {
    return new InvalidUrl(raw);
  }

  public static String urlFileName(String raw) {
    try {
      URL url = new URL(raw);
      String path = url.getPath();
      int nameEnd = path.length() - 1;
      while (nameEnd >= 0 && path.charAt(nameEnd) == '/') {
        nameEnd--;
      }
      if (nameEnd < 0) {
        return "";
      }
      String name = path.substring(path.lastIndexOf('/', nameEnd) + 1, nameEnd + 1);
      return name;
    } catch (MalformedURLException e) {
      return "";
    }
  }

  /**
   * A lazily evaluated URL that can also return its path. If the URL is invalid the path will be
   * {@code null}.
   */
  public abstract static class LazyUrl implements CharSequence, Supplier<String> {
    protected String lazy;

    protected LazyUrl(String lazy) {
      this.lazy = lazy;
    }

    /**
     * The path component of this URL.
     *
     * @return The path if valid or {@code null} if invalid
     */
    public abstract String path();

    @Override
    public String toString() {
      String str = lazy;
      if (str == null) {
        str = lazy = get();
      }
      return str;
    }

    @Override
    public int length() {
      return toString().length();
    }

    @Override
    public char charAt(int index) {
      return toString().charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return toString().subSequence(start, end);
    }

    @Override
    public int hashCode() {
      return toString().hashCode();
    }
  }

  private static class ValidUrl extends LazyUrl {
    private final String scheme;
    private final String host;
    private final int port;
    private final String path;

    private ValidUrl(String scheme, String host, int port, String path) {
      super(null);
      this.scheme = scheme;
      this.host = host;
      this.port = port;
      if (null == path || path.isEmpty()) {
        this.path = "";
      } else {
        this.path = path;
      }
    }

    @Override
    public String path() {
      return path;
    }

    @Override
    public String get() {
      String res = lazy;
      return res != null ? res : buildURL(scheme, host, port, path);
    }
  }

  private static class InvalidUrl extends LazyUrl {
    public InvalidUrl(String raw) {
      super(String.valueOf(raw));
    }

    @Override
    public String path() {
      return null;
    }

    @Override
    public String get() {
      return lazy;
    }
  }

  /**
   * Concatenate two URI parts to form the complete one. Mostly used for apache http client
   * instrumentations
   *
   * @param schemeHostPort the first part (usually <code>http://host:port</code>)
   * @param theRest the rest of the uri (e.g. <code>/path?query#fragment</code>
   * @return the full URI or <code>null</code> if fails to parse
   */
  public static URI safeConcat(final String schemeHostPort, final String theRest) {
    if (schemeHostPort == null && theRest == null) {
      return null;
    }
    final String part1 = schemeHostPort != null ? schemeHostPort : "";
    final String part2 = theRest != null ? theRest : "";
    if (part2.startsWith(part1)) {
      return safeParse(part2);
    }
    final boolean addSlash = !(part2.startsWith("/") || part1.endsWith("/"));
    final StringBuilder sb =
        new StringBuilder(part1.length() + part2.length() + (addSlash ? 1 : 0));
    PropagationUtils.onStringBuilderAppend(part1, sb.append(part1));
    if (addSlash) {
      // it happens for http async client 4 with relative URI
      sb.append('/');
    }
    PropagationUtils.onStringBuilderAppend(part2, sb.append(part2));
    return safeParse(PropagationUtils.onStringBuilderToString(sb, sb.toString()));
  }
}
