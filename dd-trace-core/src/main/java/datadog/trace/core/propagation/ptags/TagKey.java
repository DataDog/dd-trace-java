package datadog.trace.core.propagation.ptags;

import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.cache.DDPartialKeyCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class TagKey extends TagElement {
  private static final Logger log = LoggerFactory.getLogger(TagKey.class);
  private static final DDPartialKeyCache<String, TagKey> keyCache =
      DDCaches.newFixedSizePartialKeyCache(64);

  static TagKey from(String s) {
    if (s == null || isHeaderInvalid(s, 0, s.length(), null)) {
      if (log.isDebugEnabled()) {
        log.debug("Invalid header s: {}", s);
      }
      return null;
    }
    return keyCache.computeIfAbsent(
        s, 0, s.length(), TagKey::hash, TagKey::compare, TagKey::produce);
  }

  static TagKey from(Encoding encoding, String s) {
    if (isHeaderInvalid(encoding, s)) {
      if (log.isDebugEnabled()) {
        log.debug("Invalid header h: {} s: {}", encoding, s);
      }
      return null;
    }
    int pl = encoding.getPrefixLength();
    return keyCache.computeIfAbsent(
        s, pl, s.length(), TagKey::hash, TagKey::compare, TagKey::produce);
  }

  static TagKey from(Encoding encoding, String s, int start, int end) {
    if (isHeaderInvalid(encoding, s, start, end)) {
      if (log.isDebugEnabled()) {
        log.debug("Invalid header h: {} s: {} b: {} e: {}", encoding, s, start, end);
      }
      return null;
    }
    int pl = encoding.getPrefixLength();
    return keyCache.computeIfAbsent(
        s, start + pl, end, TagKey::hash, TagKey::compare, TagKey::produce);
  }

  private static boolean isHeaderInvalid(Encoding encoding, String s) {
    if (encoding == null || s == null) {
      return true;
    }
    return isHeaderInvalid(encoding, s, 0, s.length());
  }

  private static boolean isHeaderInvalid(Encoding encoding, String s, int start, int end) {
    if (encoding == null || s == null) {
      return true;
    }
    return isHeaderInvalid(s, start, end, encoding.getPrefix());
  }

  private static boolean isHeaderInvalid(String s, int start, int end, String prefix) {
    int pl = prefix == null ? 0 : prefix.length();
    int sl = s.length();
    return start < 0
        || end <= 0
        || (end - start) <= pl
        || sl <= pl
        || sl < end
        || (prefix != null && !s.startsWith(prefix, start));
  }

  private static int hash(String s, int start, int end) {
    int h = 0;
    end = Integer.min(s.length(), end);
    if (start >= 0 && end > 0) {
      for (int i = start; i < end; i++) {
        h = 31 * h + s.charAt(i);
      }
    }
    return h;
  }

  private static boolean compare(String s, int start, int end, TagKey tagKey) {
    end = Integer.min(s.length(), end);
    if (start < 0 || end < 0 || end - start != tagKey.length()) {
      return false;
    }
    boolean eq = true;
    for (int i = start, j = 0; eq && i < end; i++, j++) {
      eq = s.charAt(i) == tagKey.charAt(j);
    }
    return eq;
  }

  private static TagKey produce(String s, int hash, int start, int end) {
    return new TagKey(s, start, end);
  }

  private final String none;
  private final String[] keys = new String[Encoding.getNumValues()];

  TagKey(String s, int start, int end) {
    none = (start == 0 && end == s.length()) ? s : s.substring(start, end);
    for (Encoding p : Encoding.getCachedValues()) {
      keys[p.ordinal()] = p.getPrefix() + none;
    }
  }

  CharSequence forType(Encoding encoding) {
    return keys[encoding.ordinal()];
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    TagKey tagKey = (TagKey) o;
    return none.equals(tagKey.none);
  }

  @Override
  public int hashCode() {
    return none.hashCode();
  }

  @Override
  public String toString() {
    return none;
  }

  @Override
  public int length() {
    return none.length();
  }

  @Override
  public char charAt(int index) {
    return none.charAt(index);
  }

  @Override
  public CharSequence subSequence(int start, int end) {
    return none.subSequence(start, end);
  }
}
