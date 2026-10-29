package datadog.trace.bootstrap.regex;

import java.util.regex.Pattern;

/**
 * Process-wide cache of compiled {@link Pattern}s, shared across classloaders.
 *
 * <p>CONTRACT STUB: compiles on every call. Stream A replaces the body with the identity-keyed,
 * admission-on-second-sighting cache described in REGEX_CACHE_POC.md. Signatures are frozen.
 */
public final class RegexCache {

  private RegexCache() {}

  public static Pattern get(String regex) {
    return Pattern.compile(regex);
  }

  public static Pattern get(String regex, int flags) {
    return Pattern.compile(regex, flags);
  }

  /** Lookups served from the cache. */
  public static long hits() {
    return 0;
  }

  /** Lookups that compiled a pattern (cache miss, whether or not it was then admitted). */
  public static long misses() {
    return 0;
  }

  /** Entries admitted into the cache. */
  public static long admissions() {
    return 0;
  }
}
