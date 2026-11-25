package datadog.trace.bootstrap.jit;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;

/**
 * Drop-in replacements for name-to-object lookups whose result is fixed by the JDK.
 *
 * <p>Charset names: the standard charsets come from the JDK's standard provider, which is consulted
 * first and can't be overridden, so a canonical standard name always resolves to the same {@link
 * Charset} instance as {@link StandardCharsets}. The name and {@code Charset} overloads of {@code
 * String.getBytes} and the {@code String} constructors encode and decode the same way (replacing
 * malformed input). Only canonical names, compared ASCII-only and case-insensitively like the JDK,
 * take the fast path; aliases, unknown names and nulls go to the original method, which throws its
 * own exceptions.
 *
 * <p>Zone IDs: only IDs whose rules can't change are cached ({@code Z}, {@code UTC}, {@code GMT},
 * {@code UT}, offsets and those prefixes with an offset). Region IDs get their rules from a
 * provider that may refresh them, so they always go to {@link ZoneId#of}. {@code ZoneId} is
 * value-based, so returning a cached instance is allowed.
 */
public final class LookupRewrites {
  private LookupRewrites() {}

  private static final TieredCache ZONES = new TieredCache();

  /** {@link String#getBytes(String)}. */
  public static byte[] getBytes(String s, String charsetName) throws UnsupportedEncodingException {
    if (null != s) {
      Charset charset = standardCharset(charsetName);
      if (null != charset) {
        return s.getBytes(charset);
      }
    }
    return s.getBytes(charsetName);
  }

  /** {@link String#String(byte[], String)}. */
  public static String newString(byte[] bytes, String charsetName)
      throws UnsupportedEncodingException {
    if (null != bytes) {
      Charset charset = standardCharset(charsetName);
      if (null != charset) {
        return new String(bytes, charset);
      }
    }
    return new String(bytes, charsetName);
  }

  /** {@link String#String(byte[], int, int, String)}. */
  public static String newString(byte[] bytes, int offset, int length, String charsetName)
      throws UnsupportedEncodingException {
    if (null != bytes) {
      Charset charset = standardCharset(charsetName);
      if (null != charset) {
        return new String(bytes, offset, length, charset); // same bounds check and message
      }
    }
    return new String(bytes, offset, length, charsetName);
  }

  /** {@link Charset#forName(String)}. */
  public static Charset forName(String charsetName) {
    Charset charset = standardCharset(charsetName);
    return null != charset ? charset : Charset.forName(charsetName);
  }

  /** {@link ZoneId#of(String)}. */
  public static ZoneId zoneOf(String zoneId) {
    if (null != zoneId && hasFixedRules(zoneId)) {
      Object cached = ZONES.get(zoneId);
      if (cached instanceof ZoneId) {
        return (ZoneId) cached;
      }
      ZoneId zone = ZoneId.of(zoneId); // throws for invalid IDs, which are never cached
      if (cached == TieredCache.PROMOTE) {
        ZONES.put(zoneId, zone);
      }
      return zone;
    }
    return ZoneId.of(zoneId);
  }

  /** Whether {@link ZoneId#of} builds this ID's rules from a fixed offset, not a provider. */
  static boolean hasFixedRules(String zoneId) {
    if (zoneId.isEmpty()) {
      return false;
    }
    char first = zoneId.charAt(0);
    if (first == '+' || first == '-') {
      return true; // ZoneOffset.of
    }
    if (zoneId.equals("Z")) {
      return true;
    }
    // a prefix alone or followed by a sign; otherwise (e.g. "GMT0") it's a provider region ID
    if (zoneId.startsWith("UTC") || zoneId.startsWith("GMT")) {
      return prefixAloneOrSigned(zoneId, 3);
    }
    return zoneId.startsWith("UT") && prefixAloneOrSigned(zoneId, 2);
  }

  private static boolean prefixAloneOrSigned(String zoneId, int prefix) {
    if (zoneId.length() == prefix) {
      return true;
    }
    char next = zoneId.charAt(prefix);
    return next == '+' || next == '-';
  }

  /** The standard charset with this canonical name, or {@code null}. */
  static Charset standardCharset(String name) {
    if (null == name) {
      return null;
    }
    switch (name.length()) {
      case 5:
        return asciiEqualsIgnoreCase(name, "utf-8") ? StandardCharsets.UTF_8 : null;
      case 6:
        return asciiEqualsIgnoreCase(name, "utf-16") ? StandardCharsets.UTF_16 : null;
      case 8:
        if (asciiEqualsIgnoreCase(name, "us-ascii")) {
          return StandardCharsets.US_ASCII;
        }
        if (asciiEqualsIgnoreCase(name, "utf-16be")) {
          return StandardCharsets.UTF_16BE;
        }
        return asciiEqualsIgnoreCase(name, "utf-16le") ? StandardCharsets.UTF_16LE : null;
      case 10:
        return asciiEqualsIgnoreCase(name, "iso-8859-1") ? StandardCharsets.ISO_8859_1 : null;
      default:
        return null;
    }
  }

  /** Like the JDK's charset-name comparison: only ASCII letters fold case. */
  private static boolean asciiEqualsIgnoreCase(String s, String lowerCase) {
    for (int i = 0; i < lowerCase.length(); i++) {
      char c = s.charAt(i);
      if (c >= 'A' && c <= 'Z') {
        c = (char) (c + ('a' - 'A'));
      }
      if (c != lowerCase.charAt(i)) {
        return false;
      }
    }
    return true;
  }
}
