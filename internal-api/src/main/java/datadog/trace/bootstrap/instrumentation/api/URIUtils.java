package datadog.trace.bootstrap.instrumentation.api;

import static datadog.trace.api.telemetry.LogCollector.EXCLUDE_TELEMETRY;

import datadog.trace.api.iast.util.PropagationUtils;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
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

  public static URI safeParse(final String unparsed) {
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
