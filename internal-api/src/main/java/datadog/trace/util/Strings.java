package datadog.trace.util;

import static java.nio.charset.StandardCharsets.US_ASCII;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nullable;

public final class Strings {

  private static final byte[] HEX_DIGITS = {
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
  };

  /** com.foo.Bar -> com/foo/Bar.class */
  public static String getResourceName(final String className) {
    if (!className.endsWith(".class")) {
      return className.replace('.', '/') + ".class";
    } else {
      return className;
    }
  }

  /** com/foo/Bar.class -> com.foo.Bar */
  public static String getClassName(final String resourceName) {
    if (resourceName.endsWith(".class")) {
      return resourceName.substring(0, resourceName.length() - 6).replace('/', '.');
    }
    return resourceName.replace('/', '.');
  }

  /** com.foo.Bar -> com/foo/Bar */
  public static String getInternalName(final String className) {
    return className.replace('.', '/');
  }

  /** com.foo.Bar -> com.foo */
  public static String getPackageName(final String className) {
    int lastDot = className.lastIndexOf('.');
    return lastDot < 0 ? "" : className.substring(0, lastDot);
  }

  /** com.foo.Bar -> Bar */
  public static String getSimpleName(final String className) {
    return className.substring(className.lastIndexOf('.') + 1);
  }

  // reimplementation of string functions without regex
  public static String replace(String str, String delimiter, String replacement) {
    StringBuilder sb = new StringBuilder(str);
    int matchIndex, curIndex = 0;
    while ((matchIndex = sb.indexOf(delimiter, curIndex)) != -1) {
      sb.replace(matchIndex, matchIndex + delimiter.length(), replacement);
      curIndex = matchIndex + replacement.length();
    }
    return sb.toString();
  }

  public static String replaceFirst(String str, String delimiter, String replacement) {
    StringBuilder sb = new StringBuilder(str);
    int i = sb.indexOf(delimiter);
    if (i != -1) {
      sb.replace(i, i + delimiter.length(), replacement);
    }
    return sb.toString();
  }

  public static String sha256(String input) throws NoSuchAlgorithmException {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
    StringBuilder hexString = new StringBuilder(2 * hash.length);
    for (byte b : hash) {
      String hex = Integer.toHexString(0xFF & b);
      if (hex.length() == 1) {
        hexString.append('0');
      }
      hexString.append(hex);
    }
    return hexString.toString();
  }

  public static String truncate(String input, int limit) {
    return (String) truncate((CharSequence) input, limit);
  }

  public static CharSequence truncate(CharSequence input, int limit) {
    if (input == null || input.length() <= limit) {
      return input;
    }
    return input.subSequence(0, limit);
  }

  /**
   * Truncates a pre-encoded {@link UTF8BytesString}. Returns the same instance when within the
   * limit, so callers writing it back out keep the zero-copy fast path; only the rare over-limit
   * case re-encodes the truncated value.
   */
  public static UTF8BytesString truncate(UTF8BytesString input, int limit) {
    if (input == null || input.length() <= limit) {
      return input;
    }
    return UTF8BytesString.create(input.subSequence(0, limit));
  }

  /**
   * Checks that a string is not blank, i.e. contains at least one character that is not a
   * whitespace
   *
   * @param s The string to be checked
   * @return {@code true} if string is not blank, {@code false} otherwise (string is {@code null},
   *     empty, or contains only whitespace characters)
   */
  public static boolean isNotBlank(String s) {
    if (s == null || s.isEmpty()) {
      return false;
    }

    // the code below traverses string characters one by one
    // and checks if there is any character that is not a whitespace (space, tab, newline, etc);
    final int length = s.length();
    for (int offset = 0; offset < length; ) {
      // codepoints are used instead of chars, to properly handle non-unicode symbols
      final int codepoint = s.codePointAt(offset);
      if (!Character.isWhitespace(codepoint)) {
        return true;
      }
      offset += Character.charCount(codepoint);
    }

    return false;
  }

  /**
   * Checks that a string is blank, i.e. doest not contain characters or is null
   *
   * @param s The string to be checked
   * @return {@code true} if string is blank (string is {@code null}, empty, or contains only
   *     whitespace characters), {@code false} otherwise
   */
  public static boolean isBlank(String s) {
    return !isNotBlank(s);
  }

  /**
   * Generates a random string of the given length from lowercase characters a-z
   *
   * @param length length of the string
   * @return random string containing lowercase latin characters
   */
  public static String random(int length) {
    char[] c = new char[length];
    for (int i = 0; i < length; i++) {
      c[i] = (char) ('a' + ThreadLocalRandom.current().nextInt(26));
    }
    return new String(c);
  }

  public static String toHexString(byte[] value) {
    if (value == null) {
      return null;
    }
    byte[] bytes = new byte[value.length * 2];
    for (int i = 0; i < value.length; i++) {
      byte v = value[i];
      bytes[i * 2] = HEX_DIGITS[(v & 0xF0) >>> 4];
      bytes[i * 2 + 1] = HEX_DIGITS[v & 0x0F];
    }
    return new String(bytes, US_ASCII);
  }

  public static String[] concat(String[] arr, String... extra) {
    if (arr.length == 0) return extra;
    if (extra.length == 0) return arr;
    String[] result = new String[arr.length + extra.length];
    System.arraycopy(arr, 0, result, 0, arr.length);
    System.arraycopy(extra, 0, result, arr.length, extra.length);
    return result;
  }

  /**
   * @return first non-blank string out of the two, {@code null} if both are blank
   */
  @Nullable
  public static String coalesce(@Nullable final String first, @Nullable final String second) {
    if (isNotBlank(first)) {
      return first;
    } else if (isNotBlank(second)) {
      return second;
    } else {
      return null;
    }
  }

  /** Low overhead replaceAll */
  public static final String replaceAll(String input, String needle, String replacement) {
    int index = input.indexOf(needle);
    if (index == -1) return input;

    int needleLen = needle.length();

    StringBuilder builder = new StringBuilder(input.length() + 10);
    builder.append(input, 0, index);
    builder.append(replacement);

    int prevIndex = index;
    index = input.indexOf(needle, index + needleLen);
    for (; index != -1; prevIndex = index, index = input.indexOf(needle, index + needleLen)) {
      builder.append(input, prevIndex + needleLen, index);
      builder.append(replacement);
    }
    builder.append(input, prevIndex + needleLen, input.length());

    return builder.toString();
  }

  /**
   * Provides a SubSequence which a view into the provided String Unlike String.subSequence (which
   * is usually just a wrapper around String.substring), this routine doesn't allocate a new String
   * or byte[]/char[].
   */
  public static final SubSequence subSequence(String str, int beginIndex) {
    return new SubSequence(str, beginIndex, str.length());
  }

  /**
   * Provides a SubSequence which a view into the provided String Unlike String.subSequence (which
   * is usually just a wrapper around String.substring), this routine doesn't allocate a new <code>
   * String</code> or <code>byte[]</code> / <code>char[]</code>.
   */
  public static final SubSequence subSequence(String str, int beginIndex, int endIndex) {
    return new SubSequence(str, beginIndex, endIndex);
  }

  /**
   * Provides an Iterable<SubSequence> where the sub-sequences are separated by <code>splitChar
   * </code>. Unlike other approaches to splitting, this routine doesn't allocate any new <code>
   * String</code> or <code>byte[]</code> / <code>char[]</code>
   */
  public static final Iterable<SubSequence> split(String str, char splitChar) {
    if (str.isEmpty()) {
      return Collections.emptyList();
    }

    int firstIndex = str.indexOf(splitChar);
    if (firstIndex == -1) {
      return Collections.singletonList(subSequence(str, 0));
    }

    return new SplitIterable(str, splitChar, firstIndex);
  }

  static final class SplitIterable implements Iterable<SubSequence> {
    private final String str;
    private final int len;
    private final char splitChar;
    private final int firstIndex;

    SplitIterable(String str, char splitChar, int firstIndex) {
      this.str = str;
      this.len = str.length();
      this.splitChar = splitChar;
      this.firstIndex = firstIndex;
    }

    @Override
    public SplitIterator iterator() {
      return new SplitIterator(this.str, this.len, this.splitChar, this.firstIndex);
    }
  }

  static final class SplitIterator implements Iterator<SubSequence> {
    private final String str;
    private final int len;
    private final char splitChar;

    private int curIndex;
    private int nextIndex;

    SplitIterator(String str, int len, char splitChar, int firstIndex) {
      this.str = str;
      this.len = len;
      this.splitChar = splitChar;

      this.curIndex = 0;
      this.nextIndex = firstIndex == -1 ? len : firstIndex;
    }

    @Override
    public boolean hasNext() {
      return (this.curIndex <= this.len);
    }

    @Override
    public SubSequence next() {
      int curIndex = this.curIndex;
      int len = this.len;

      if (curIndex > len) throw new NoSuchElementException();

      // NOTE: Experimented with returning a single mutable SubSequence
      // where the index range is updated each time.  In typical usage,
      // that was slightly worse -- likely because escape analysis was
      // able to eliminate the allocation, but that hasn't been directly
      // confirmed.
      SubSequence subSeq;

      int nextIndex = this.nextIndex;
      if (nextIndex == len - 1) {
        // Handles the case where there's a trailing separator,
        // curIndex is moved to len to represent the empty string
        // after the trailing separator

        // Next call then goes into the special case below
        subSeq = new SubSequence(this.str, curIndex, nextIndex);
        this.curIndex = len;
        this.nextIndex = len;
      } else if (curIndex == len) {
        // Handles the empty string after the trailing separator
        // curIndex is given the terminating value `len + 1`

        // Don't use SubSequence.EMPTY because it wouldn't have
        // the correct beginIndex
        subSeq = new SubSequence(this.str, len, len);
        this.curIndex = len + 1;
      } else {
        subSeq = new SubSequence(this.str, curIndex, nextIndex);

        // core advancing logic
        this.curIndex = nextIndex + 1;
        int searchIndex = this.str.indexOf(this.splitChar, nextIndex + 1);
        this.nextIndex = (searchIndex == -1) ? len : searchIndex;
      }

      return subSeq;
    }
  }

  /**
   * True if {@code needle} occurs fully within {@code s[beginIndex, endIndex)} -- a range-limited,
   * allocation-free alternative to {@code s.substring(beginIndex, endIndex).contains(needle)}.
   *
   * <p>{@code indexOf} returns the earliest occurrence at or after {@code beginIndex}; if that one
   * overshoots {@code endIndex} there is no earlier full occurrence in range, so the bound check is
   * exact.
   */
  public static boolean regionContains(String s, int beginIndex, int endIndex, String needle) {
    int idx = s.indexOf(needle, beginIndex);
    return idx >= 0 && idx + needle.length() <= endIndex;
  }

  /**
   * A {@code hashCode} consistent with {@link String#equalsIgnoreCase}: any two strings that are
   * equal ignoring case produce the same value. Same polynomial as {@link String#hashCode} but over
   * the case-folded characters, so it never allocates (no {@code toLowerCase} copy).
   *
   * <p>Uses the same two-way fold {@code String.equalsIgnoreCase} / {@code
   * String.regionMatches(ignoreCase)} use ({@code toLowerCase(toUpperCase(c))}), so the two stay
   * consistent for all inputs, not just ASCII — pairing a one-way fold here with {@code
   * equalsIgnoreCase} would risk silent false misses on the Unicode characters where they diverge.
   *
   * <p>Folds per {@code char} (UTF-16 unit), which is exactly what {@code equalsIgnoreCase} itself
   * does — so a supplementary case pair (e.g. U+10400 / U+10428) is treated as <i>distinct</i> by
   * both, and the two remain consistent. This mirrors {@code equalsIgnoreCase} rather than doing
   * full code-point case folding; a code-point fold would unify pairs {@code equalsIgnoreCase} does
   * not, making the hash inconsistent with it.
   */
  public static int caseInsensitiveHashCode(String s) {
    int h = 0;
    for (int i = 0, len = s.length(); i < len; ++i) {
      h = 31 * h + Character.toLowerCase(Character.toUpperCase(s.charAt(i)));
    }
    return h;
  }
}
