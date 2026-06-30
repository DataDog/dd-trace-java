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

  /**
   * The same value as {@code toString().hashCode()} -- the {@link String} hash polynomial over this
   * window -- but computed directly over the backing characters so hashing a view does not
   * materialize a substring. Stays consistent with {@link #equals}: a view, its content-equal
   * {@code String}, and an equal-content view all share this hash.
   */
  @Override
  public int hashCode() {
    int h = 0;
    for (int i = this.beginIndex; i < this.endIndex; ++i) {
      h = 31 * h + this.str.charAt(i);
    }
    return h;
  }

  /**
   * Dispatches on the argument's runtime type: a {@code String} takes the {@link #equals(String)}
   * region-compare fast path; any other {@code CharSequence} is compared via {@link
   * #contentEquals(CharSequence)}. So {@code this.equals(backingStr.substring(beginIndex,
   * endIndex))} is true, and two views with equal content are equal.
   */
  @Override
  public boolean equals(Object obj) {
    if (obj instanceof String) return this.equals((String) obj);
    if (obj instanceof CharSequence) return this.contentEquals((CharSequence) obj);

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
   * Equivalent to {@code toString().contentEquals(that)}: true when {@code that} has the same
   * length and characters as this window. The general char-by-char comparison for any {@code
   * CharSequence}; prefer {@link #equals(String)} when the argument is known to be a {@code
   * String}.
   */
  public final boolean contentEquals(CharSequence that) {
    if (that == null) return false;

    int len = this.length();
    if (len != that.length()) return false;

    for (int i = 0; i < len; ++i) {
      if (this.charAt(i) != that.charAt(i)) return false;
    }
    return true;
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

  /**
   * Equivalent to {@code toString().lastIndexOf(needle)}: the offset of the last full occurrence of
   * {@code needle} within this window relative to the window start, or {@code -1} if it does not
   * occur fully in range. Searches back from {@code endIndex - needle.length()} -- the latest start
   * whose end still fits the window -- so the lower bound is a single check against {@code
   * beginIndex}.
   */
  public final int lastIndexOf(String needle) {
    int idx = this.str.lastIndexOf(needle, this.endIndex - needle.length());
    return (idx >= this.beginIndex) ? idx - this.beginIndex : -1;
  }

  /**
   * Equivalent to {@code toString().lastIndexOf(c)}: the offset of the last {@code c} within this
   * window relative to the window start, or {@code -1} if it does not occur in range.
   */
  public final int lastIndexOf(char c) {
    int idx = this.str.lastIndexOf(c, this.endIndex - 1);
    return (idx >= this.beginIndex) ? idx - this.beginIndex : -1;
  }

  /**
   * True if this sub-sequence contains {@code needle} -- the zero-copy equivalent of {@code
   * toString().contains(needle)}, with no substring materialized.
   */
  public final boolean contains(String needle) {
    return Strings.regionContains(this.str, this.beginIndex, this.endIndex, needle);
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
