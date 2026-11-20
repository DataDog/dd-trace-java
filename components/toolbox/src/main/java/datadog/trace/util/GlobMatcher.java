package datadog.trace.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;

/**
 * Matches text against a glob without a regex engine: {@code *} matches any run of characters,
 * {@code ?} matches any one character, and every other character is literal (there is no escape).
 * The whole text must match. Matching allocates nothing and never backtracks.
 *
 * <p>The results are exactly those of the regular expression the glob translates to, {@code ^} with
 * {@code *} as {@code .*}, {@code ?} as {@code .}, every other character quoted, then {@code $},
 * compiled with {@link java.util.regex.Pattern} (and {@link Pattern#CASE_INSENSITIVE} for {@link
 * #compileIgnoringCase}). That includes its edge cases:
 *
 * <ul>
 *   <li>Neither {@code *} nor {@code ?} matches a line terminator ({@code \n}, {@code \r}, {@code
 *       \u0085}, {@code  }, {@code  }).
 *   <li>{@code ?} matches one code point, so a surrogate pair counts as one character.
 *   <li>Ignoring case folds ASCII letters only, as {@code CASE_INSENSITIVE} does without {@code
 *       UNICODE_CASE}.
 * </ul>
 *
 * <p>The rare cases that would complicate matching, a glob that contains a line terminator or a
 * surrogate, and a {@code ?} facing a surrogate in the text, are left to that {@code Pattern},
 * compiled on first use. Thread-safe.
 */
public final class GlobMatcher {
  // Results of placing one segment; a match is reported as MATCH or as a position.
  private static final int MISMATCH = -1;
  private static final int FALLBACK = -2;
  private static final int MATCH = 0;

  private final String glob;
  private final boolean ignoreCase;
  // The literal runs between the stars, as each char's lower and upper case (the same char when
  // case matters or it isn't an ASCII letter). '?' in them is the one-character wildcard.
  private final char[][] lower;
  private final char[][] upper;
  private final boolean leadingStar;
  private final boolean trailingStar;
  private final int minLength;
  private final int maxLength;
  private final boolean regexOnly;
  private volatile Pattern fallback;

  private GlobMatcher(final String glob, final boolean ignoreCase) {
    this.glob = glob;
    this.ignoreCase = ignoreCase;
    final List<String> parts = new ArrayList<>();
    final StringBuilder segment = new StringBuilder();
    boolean star = false;
    int questions = 0;
    boolean regexOnly = false;
    for (int i = 0; i < glob.length(); i++) {
      final char c = glob.charAt(i);
      if (c == '*') {
        star = true;
        if (segment.length() > 0) {
          parts.add(segment.toString());
          segment.setLength(0);
        }
        continue;
      }
      if (c == '?') {
        questions++;
      }
      regexOnly |= isLineTerminator(c) || Character.isSurrogate(c);
      segment.append(c);
    }
    if (segment.length() > 0) {
      parts.add(segment.toString());
    }
    final int count = parts.size();
    this.lower = new char[count][];
    this.upper = new char[count][];
    int min = 0;
    for (int k = 0; k < count; k++) {
      final char[] chars = parts.get(k).toCharArray();
      final char[] lo = new char[chars.length];
      final char[] up = new char[chars.length];
      for (int j = 0; j < chars.length; j++) {
        lo[j] = ignoreCase ? toLower(chars[j]) : chars[j];
        up[j] = ignoreCase ? toUpper(chars[j]) : chars[j];
      }
      lower[k] = lo;
      upper[k] = up;
      min += chars.length;
    }
    this.leadingStar = !glob.isEmpty() && glob.charAt(0) == '*';
    this.trailingStar = !glob.isEmpty() && glob.charAt(glob.length() - 1) == '*';
    this.minLength = min;
    // Without a star, each '?' takes one or two chars (a surrogate pair is one code point).
    this.maxLength = star ? Integer.MAX_VALUE : min + questions;
    this.regexOnly = regexOnly;
  }

  /**
   * Compiles a case-sensitive glob.
   *
   * @param glob the glob: {@code *} for any run, {@code ?} for any one character.
   * @return the matcher.
   */
  @Nonnull
  public static GlobMatcher compile(@Nonnull final String glob) {
    return new GlobMatcher(glob, false);
  }

  /**
   * Compiles a glob that ignores the case of ASCII letters.
   *
   * @param glob the glob: {@code *} for any run, {@code ?} for any one character.
   * @return the matcher.
   */
  @Nonnull
  public static GlobMatcher compileIgnoringCase(@Nonnull final String glob) {
    return new GlobMatcher(glob, true);
  }

  /**
   * Gets the glob this matcher was compiled from.
   *
   * @return the glob.
   */
  @Nonnull
  public String glob() {
    return glob;
  }

  /**
   * Tells whether the whole text matches the glob.
   *
   * @param text the text.
   * @return whether it matches.
   */
  public boolean matches(@Nonnull final CharSequence text) {
    if (regexOnly) {
      return fallback().matcher(text).matches();
    }
    final int n = text.length();
    if (n < minLength || n > maxLength) {
      return false;
    }
    final int count = lower.length;
    if (count == 0) {
      // "" matches only ""; "*" (or "**"...) matches any text without a line terminator
      return leadingStar ? !hasLineTerminator(text, 0, n) : n == 0;
    }
    // The cheapest rejections first: the anchored first and last segments have fixed positions.
    // The text still to match is [start, end), and the segments still to place [first, last].
    int start = 0;
    int end = n;
    int first = 0;
    int last = count - 1;
    if (!leadingStar) {
      final int result = forward(0, text, 0);
      if (result != MATCH) {
        return result == FALLBACK && fallbackMatches(text);
      }
      if (count == 1 && !trailingStar) {
        // no star: the one segment is the whole glob
        return n == lower[0].length;
      }
      start = lower[0].length;
      first = 1;
    }
    if (!trailingStar) {
      final int at = n - lower[last].length;
      if (at < start) {
        return false;
      }
      final int result = backward(last, text, n);
      if (result != MATCH) {
        return result == FALLBACK && fallbackMatches(text);
      }
      end = at;
      last--;
    }
    // Every gap left is a star's, which can't take a line terminator. Each middle segment goes at
    // its leftmost place: a later one only leaves less room for the rest.
    int pos = start;
    for (int k = first; k <= last; k++) {
      final int at = find(k, text, pos, end);
      if (at < 0) {
        return at == FALLBACK && fallbackMatches(text);
      }
      pos = at + lower[k].length;
    }
    return !hasLineTerminator(text, pos, end);
  }

  /**
   * Places segment {@code k} at its leftmost position in {@code [from, end)} whose gap from {@code
   * from} has no line terminator.
   *
   * @return the position, or {@link #MISMATCH} or {@link #FALLBACK}.
   */
  private int find(final int k, final CharSequence text, final int from, final int end) {
    final char[] lo = lower[k];
    final char lo0 = lo[0];
    final char up0 = upper[k][0];
    final int limit = end - lo.length;
    for (int at = from; at <= limit; at++) {
      final char c = text.charAt(at);
      if (lo0 == '?' || c == lo0 || c == up0) {
        final int result = forward(k, text, at);
        if (result == MATCH) {
          return at;
        } else if (result == FALLBACK) {
          return FALLBACK;
        }
      }
      if (isLineTerminator(c)) {
        return MISMATCH; // the star before the segment can't take it
      }
    }
    return MISMATCH;
  }

  /** Compares segment {@code k} with the text from {@code at}, left to right. */
  private int forward(final int k, final CharSequence text, final int at) {
    final char[] lo = lower[k];
    final char[] up = upper[k];
    for (int j = 0; j < lo.length; j++) {
      final int result = compare(lo[j], up[j], text.charAt(at + j));
      if (result != MATCH) {
        return result;
      }
    }
    return MATCH;
  }

  /**
   * Compares segment {@code k} with the text ending at {@code end}, right to left, so that every
   * position checked before a {@code ?} meets a surrogate is a code point position.
   */
  private int backward(final int k, final CharSequence text, final int end) {
    final char[] lo = lower[k];
    final char[] up = upper[k];
    for (int j = lo.length - 1, i = end - 1; j >= 0; j--, i--) {
      final int result = compare(lo[j], up[j], text.charAt(i));
      if (result != MATCH) {
        return result;
      }
    }
    return MATCH;
  }

  private static int compare(final char lo, final char up, final char c) {
    if (lo == '?') {
      if (Character.isSurrogate(c)) {
        return FALLBACK; // '?' takes the whole pair, so char positions stop being code points
      }
      return isLineTerminator(c) ? MISMATCH : MATCH;
    }
    return c == lo || c == up ? MATCH : MISMATCH;
  }

  private static boolean hasLineTerminator(final CharSequence text, final int from, final int to) {
    for (int i = from; i < to; i++) {
      if (isLineTerminator(text.charAt(i))) {
        return true;
      }
    }
    return false;
  }

  private boolean fallbackMatches(final CharSequence text) {
    return fallback().matcher(text).matches();
  }

  private Pattern fallback() {
    Pattern pattern = fallback;
    if (pattern == null) {
      pattern = Pattern.compile(toRegex(glob), ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
      fallback = pattern;
    }
    return pattern;
  }

  /**
   * The glob as a regex. Each literal run is quoted whole, so a surrogate pair in it stays one code
   * point.
   */
  private static String toRegex(final String glob) {
    final StringBuilder regex = new StringBuilder(glob.length() + 16).append('^');
    int run = 0;
    for (int i = 0; i <= glob.length(); i++) {
      final char c = i < glob.length() ? glob.charAt(i) : '*';
      if (c == '*' || c == '?') {
        if (run < i) {
          regex.append(Pattern.quote(glob.substring(run, i)));
        }
        run = i + 1;
        if (i < glob.length()) {
          regex.append(c == '*' ? ".*" : ".");
        }
      }
    }
    return regex.append('$').toString();
  }

  private static char toLower(final char c) {
    return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
  }

  private static char toUpper(final char c) {
    return c >= 'a' && c <= 'z' ? (char) (c - ('a' - 'A')) : c;
  }

  /** The line terminators {@code java.util.regex}'s {@code .} doesn't match, without UNIX_LINES. */
  private static boolean isLineTerminator(final char c) {
    if (c <= '\r') {
      return c == '\n' || c == '\r';
    }
    return c >= '\u0085' && (c == '\u0085' || c == ' ' || c == ' ');
  }

  @Override
  public String toString() {
    return glob;
  }
}
