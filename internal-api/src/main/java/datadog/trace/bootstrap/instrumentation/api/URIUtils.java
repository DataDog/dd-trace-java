package datadog.trace.bootstrap.instrumentation.api;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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
      return URI.create(unparsed);
    } catch (final IllegalArgumentException exception) {
      LOGGER.debug("Unable to parse request uri {}", unparsed, exception);
      return null;
    }
  }
}
