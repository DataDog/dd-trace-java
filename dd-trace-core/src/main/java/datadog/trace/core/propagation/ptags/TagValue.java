package datadog.trace.core.propagation.ptags;

import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.cache.DDPartialKeyCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class TagValue extends TagElement {
  private static final Logger log = LoggerFactory.getLogger(TagValue.class);
  private static final DDPartialKeyCache<CharSequence, TagValue> valueCache =
      DDCaches.newFixedSizePartialKeyCache(128);
  private static final int DD_SOURCE = Encoding.DATADOG.ordinal();

  static TagValue from(CharSequence s) {
    return from(Encoding.DATADOG, s);
  }

  static TagValue from(Encoding encoding, CharSequence s) {
    return from(encoding, s, s == null ? -1 : 0, s == null ? -1 : s.length());
  }

  static TagValue from(Encoding encoding, CharSequence s, int start, int end) {
    if (s == null || isValueInvalid(s, start, end)) {
      if (log.isDebugEnabled()) {
        log.debug("Invalid header h: {} s: {} b: {} e: {}", encoding, s, start, end);
      }
      return null;
    }
    if (encoding == Encoding.W3C) {
      return valueCache.computeIfAbsent(
          s, start, end, TagValue::hashW3C, TagValue::compareW3C, TagValue::produceW3C);
    } else {
      return valueCache.computeIfAbsent(
          s, start, end, TagValue::hashDD, TagValue::compareDD, TagValue::produceDD);
    }
  }

  private static boolean isValueInvalid(CharSequence s, int start, int end) {
    return start < 0 || end <= 0 || s.length() < end;
  }

  /** Hash a DD-encoded input by converting each char to W3C (the canonical form). */
  private static int hashDD(CharSequence s, int start, int end) {
    int h = 0;
    end = Integer.min(s.length(), end);
    if (start >= 0 && end > 0) {
      for (int i = start; i < end; i++) {
        h = 31 * h + convertDDtoW3C(s.charAt(i));
      }
    }
    return h;
  }

  /** Hash a W3C-encoded input directly (already in canonical form). */
  private static int hashW3C(CharSequence s, int start, int end) {
    int h = 0;
    end = Integer.min(s.length(), end);
    if (start >= 0 && end > 0) {
      for (int i = start; i < end; i++) {
        h = 31 * h + s.charAt(i);
      }
    }
    return h;
  }

  /**
   * Compare a DD-encoded input against a cached TagValue. The input is already in DD form, so we
   * compare directly against the TagValue's DD representation (obtained once before the loop).
   */
  private static boolean compareDD(CharSequence s, int start, int end, TagValue tagValue) {
    end = Integer.min(s.length(), end);
    int len = end - start;
    if (start < 0 || end < 0 || len != tagValue.length()) {
      return false;
    }
    CharSequence dd = tagValue.forType(Encoding.DATADOG);
    for (int i = start, j = 0; i < end; i++, j++) {
      if (s.charAt(i) != dd.charAt(j)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Compare a W3C-encoded input against a cached TagValue. Converts each W3C input char to DD form
   * and compares against the TagValue's DD representation (obtained once before the loop).
   */
  private static boolean compareW3C(CharSequence s, int start, int end, TagValue tagValue) {
    end = Integer.min(s.length(), end);
    int len = end - start;
    if (start < 0 || end < 0 || len != tagValue.length()) {
      return false;
    }
    CharSequence dd = tagValue.forType(Encoding.DATADOG);
    for (int i = start, j = 0; i < end; i++, j++) {
      if (convertW3CtoDD(s.charAt(i)) != dd.charAt(j)) {
        return false;
      }
    }
    return true;
  }

  private static TagValue produceDD(CharSequence s, int hash, int start, int end) {
    return new TagValue(Encoding.DATADOG, hash, s, start, end);
  }

  private static TagValue produceW3C(CharSequence s, int hash, int start, int end) {
    return new TagValue(Encoding.W3C, hash, s, start, end);
  }

  private interface CharConverter {
    char convert(char c);
  }

  private static char convertDDtoW3C(char c) {
    if (c == ',' || c == ';' || c == '~') {
      return '_';
    } else if (c == '=') {
      return '~';
    }
    return c;
  }

  private static char identity(char c) {
    return c;
  }

  private static char convertW3CtoDD(char c) {
    if (c == '~') {
      return '=';
    }
    return c;
  }

  private final CharSequence[] values = new CharSequence[Encoding.getNumValues()];
  private final int source;
  private final int hash;

  TagValue(Encoding encoding, int hash, CharSequence s, int start, int end) {
    this.source = encoding.ordinal();
    this.hash = hash;
    values[source] =
        (start == 0 && end == s.length())
            ? s
            : new StringBuilder(end - start).append(s, start, end).toString();
  }

  CharSequence forType(Encoding encoding) {
    int ordinal = encoding.ordinal();
    CharSequence cs = values[ordinal];
    if (cs == null) {
      CharSequence from = values[source];
      int len = from.length();
      CharConverter cc = source == DD_SOURCE ? TagValue::convertDDtoW3C : TagValue::convertW3CtoDD;
      StringBuilder sb = null;
      for (int i = 0; i < len; i++) {
        char c = from.charAt(i);
        char tc = cc.convert(c);
        if (tc != c && sb == null) {
          sb = new StringBuilder(len);
          sb.append(from, 0, i);
        }
        if (sb != null) {
          sb.append(tc);
        }
      }
      cs = values[ordinal] = sb == null ? from : sb.toString();
    }
    return cs;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    TagValue ov = (TagValue) o;
    if (hash != ov.hash) return false;
    CharSequence cst = values[source];
    CharSequence cso = ov.values[ov.source];
    int len = cst.length();
    if (len != cso.length()) return false;
    if (source == ov.source) {
      for (int i = 0; i < len; i++) {
        if (cst.charAt(i) != cso.charAt(i)) return false;
      }
    } else {
      CharConverter cct = source == DD_SOURCE ? TagValue::identity : TagValue::convertW3CtoDD;
      CharConverter cco = ov.source == DD_SOURCE ? TagValue::identity : TagValue::convertW3CtoDD;
      for (int i = 0; i < len; i++) {
        if (cct.convert(cst.charAt(i)) != cco.convert(cso.charAt(i))) return false;
      }
    }
    return true;
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public String toString() {
    return values[source].toString();
  }

  @Override
  public int length() {
    return values[source].length();
  }

  @Override
  public char charAt(int index) {
    if (source == DD_SOURCE) {
      return values[source].charAt(index);
    } else {
      return convertW3CtoDD(values[source].charAt(index));
    }
  }

  @Override
  public CharSequence subSequence(int start, int end) {
    return values[source].subSequence(start, end);
  }

  public int indexOf(char c) {
    c = source == DD_SOURCE ? c : convertDDtoW3C(c);
    CharSequence cs = values[source];
    int len = cs.length();
    int index = -1;
    for (int i = 0; i < len; i++) {
      if (cs.charAt(i) == c) {
        index = i;
      }
    }
    return index;
  }
}
