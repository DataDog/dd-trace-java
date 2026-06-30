package datadog.trace.util;

/**
 * A <code>CharSequence</code> that is a view into a sub-sequence of a <code>String</code>. Unlike
 * <code>String.subSequence</code>, this class doesn't allocate an additional <code>String</code>,
 * <code>char[]</code>, or <code>byte[]</code>.
 *
 * <p>Why that matters: <code>String.substring</code> / <code>subSequence</code> copy the selected
 * range into a fresh backing array on every call, so scanning or splitting a string into many
 * pieces — parsing headers, tags, or query strings on a hot path — allocates one intermediate
 * <code>String</code> per slice. A <code>SubSequence</code> is a zero-copy window over the original
 * (an offset + length into the existing backing array), so the same parse allocates nothing per
 * slice. Use it for transient, read-only views; materialize a real <code>String</code> only when
 * the value must be retained or handed off.
 */
public final class SubSequence implements CharSequence {
  public static final SubSequence EMPTY = new SubSequence("", 0, 0);

  /**
   * SubSequence from <code>beginIndex</code> to end of <code>str</code> Equivalent to
   * str.subSequence(str, startIndex)
   */
  public static final SubSequence of(String str, int startIndex) {
    return new SubSequence(str, startIndex, str.length());
  }

  /**
   * SubSequence from <code>beginIndex</code> inclusive to <code>endIndex</code> exclusive of <code>
   * str</code> Equivalent to str.subSequence(str, startIndex, endIndex)
   */
  public static final SubSequence of(String str, int startIndex, int endIndex) {
    return new SubSequence(str, startIndex, endIndex);
  }

  private final String str;
  private final int beginIndex;
  private final int endIndex;

  private String cachedSubstr = null;

  SubSequence(String str, int startIndex, int endIndex) {
    this.str = str;
    this.beginIndex = startIndex;
    this.endIndex = endIndex;
  }

  /** Beginning index of the subseqence in the backing String - can be useful in text processing */
  public int beginIndex() {
    return this.beginIndex;
  }

  /** Ending index of the subsequence in the backing String - can be useful in text processing */
  public int endIndex() {
    return this.endIndex;
  }

  @Override
  public char charAt(int index) {
    return this.str.charAt(this.beginIndex + index);
  }

  @Override
  public int length() {
    return this.endIndex - this.beginIndex;
  }

  @Override
  public SubSequence subSequence(int start, int end) {
    int newBeginIndex = this.beginIndex + start;
    int newEndIndex = this.beginIndex + start + end;

    return new SubSequence(this.str, newBeginIndex, newEndIndex);
  }

  /** Appends this SubSequence to the StringBuilder Equivalent to builder.append(this) but faster */
  public void appendTo(StringBuilder builder) {
    int beginIndex = this.beginIndex;
    int endIndex = this.endIndex;

    // Guards against the special case empty SubSequence at this.str.length
    if (beginIndex != endIndex) builder.append(this.str, beginIndex, endIndex);
  }

  /** Returns the hash code as <code>backingStr.substr(beginIndex, endIndex).hashCode()</code> */
  @Override
  public int hashCode() {
    return this.toString().hashCode();
  }

  /**
   * Also handles String comparisons this.equals(backingStr.substr(beginIndex, endIndex)) is true
   */
  @Override
  public boolean equals(Object obj) {
    // Route Strings to the region-compare fast path; fall back to the charAt loop only for
    // genuine non-String CharSequences.
    if (obj instanceof String) return this.equals((String) obj);
    if (obj instanceof CharSequence) return this.equals((CharSequence) obj);

    return false;
  }

  /**
   * Equivalent to {@code toString().equals(other)} -- compares this window against the whole {@code
   * other} String with no substring materialized. Delegates to {@link String#regionMatches} so it
   * reuses the JDK's backing-array compare rather than a per-char loop.
   */
  public final boolean equals(String other) {
    return other != null
        && other.length() == this.length()
        && this.str.regionMatches(this.beginIndex, other, 0, other.length());
  }

  /**
   * Case-insensitive counterpart of {@link #equals(String)}. Like {@link
   * String#equalsIgnoreCase(String)}, a {@code null} argument is {@code false} rather than an
   * error.
   */
  public final boolean equalsIgnoreCase(String other) {
    return other != null
        && other.length() == this.length()
        && this.str.regionMatches(true, this.beginIndex, other, 0, other.length());
  }

  /**
   * Equivalent to {@code toString().startsWith(prefix)}. The window guard ({@code prefix.length()
   * <= length()}) keeps the delegated read inside {@code [beginIndex, endIndex)}.
   */
  public final boolean startsWith(String prefix) {
    return prefix.length() <= this.length() && this.str.startsWith(prefix, this.beginIndex);
  }

  /**
   * Equivalent to {@code length() > 0 && charAt(0) == c}, the single-character {@link
   * #startsWith(String)}.
   */
  public final boolean startsWith(char c) {
    return this.beginIndex < this.endIndex && this.str.charAt(this.beginIndex) == c;
  }

  /**
   * Equivalent to {@code toString().endsWith(suffix)}. Implemented as a prefix match anchored at
   * {@code endIndex - suffix.length()} so the read stays inside this window.
   */
  public final boolean endsWith(String suffix) {
    int suffixLen = suffix.length();
    return suffixLen <= this.length() && this.str.startsWith(suffix, this.endIndex - suffixLen);
  }

  /**
   * Equivalent to {@code length() > 0 && charAt(length() - 1) == c}, the single-character {@link
   * #endsWith(String)}.
   */
  public final boolean endsWith(char c) {
    return this.beginIndex < this.endIndex && this.str.charAt(this.endIndex - 1) == c;
  }

  /**
   * Equivalent to {@code toString().indexOf(needle)}: the offset of the first full occurrence of
   * {@code needle} within this window relative to the window start, or {@code -1} if it does not
   * occur fully in range. {@link String#indexOf(String, int)} returns the earliest occurrence at or
   * after {@code beginIndex}, so a single bound check against {@code endIndex} is exact.
   */
  public final int indexOf(String needle) {
    int idx = this.str.indexOf(needle, this.beginIndex);
    return (idx >= 0 && idx + needle.length() <= this.endIndex) ? idx - this.beginIndex : -1;
  }

  /**
   * Equivalent to {@code toString().indexOf(c)}: the offset of the first {@code c} within this
   * window relative to the window start, or {@code -1} if it does not occur in range.
   */
  public final int indexOf(char c) {
    int idx = this.str.indexOf(c, this.beginIndex);
    return (idx >= 0 && idx < this.endIndex) ? idx - this.beginIndex : -1;
  }

  public final boolean equals(CharSequence that) {
    int thisLen = this.length();
    int thatLen = that.length();

    if (thisLen != thatLen) return false;

    for (int i = 0; i < Math.min(this.length(), that.length()); ++i) {
      if (this.charAt(i) != that.charAt(i)) return false;
    }
    return true;
  }

  /**
   * True if this sub-sequence contains {@code needle} -- the zero-copy equivalent of {@code
   * toString().contains(needle)}, with no substring materialized.
   */
  public final boolean contains(String needle) {
    return Strings.regionContains(this.str, this.beginIndex, this.endIndex, needle);
  }

  /** Case-insensitive content comparison; mirrors {@link String#equalsIgnoreCase(String)}. */
  public final boolean equalsIgnoreCase(CharSequence that) {
    int len = this.length();
    if (that == null || len != that.length()) return false;

    for (int i = 0; i < len; ++i) {
      char a = this.charAt(i);
      char b = that.charAt(i);
      if (a != b) {
        // Same two-way fold String.regionMatches(ignoreCase) uses (handles locale edge cases).
        char au = Character.toUpperCase(a);
        char bu = Character.toUpperCase(b);
        if (au != bu && Character.toLowerCase(au) != Character.toLowerCase(bu)) {
          return false;
        }
      }
    }
    return true;
  }

  /** True if this sub-sequence begins with {@code prefix} (content comparison, no allocation). */
  public final boolean startsWith(CharSequence prefix) {
    int prefixLen = prefix.length();
    if (prefixLen > this.length()) return false;

    for (int i = 0; i < prefixLen; ++i) {
      if (this.charAt(i) != prefix.charAt(i)) return false;
    }
    return true;
  }

  @Override
  public String toString() {
    String cached = this.cachedSubstr;
    if (cached != null) return cached;

    int beginIndex = this.beginIndex;
    int endIndex = this.endIndex;

    String substr = (beginIndex == endIndex) ? "" : this.str.substring(beginIndex, endIndex);
    this.cachedSubstr = substr;
    return substr;
  }
}
