package datadog.trace.util;

/**
 * A <code>CharSequence</code> that is view into a sub-sequencce of a <code>String</code> Unlike
 * <code>String.subSequence</code>, this class doesn't allocate an additional <code>String</code>,
 * <code>char[]</code>, or <code>byte[]</code>
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
    if (!(obj instanceof CharSequence)) return false;

    return this.equals((CharSequence) obj);
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
