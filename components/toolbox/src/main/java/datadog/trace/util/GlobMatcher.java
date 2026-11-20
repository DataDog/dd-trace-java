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
 * <p>The two rare cases that would complicate matching, a glob that contains a line terminator and
 * a {@code ?} facing a surrogate pair in the text, are left to that {@code Pattern}, compiled on
 * first use. Thread-safe.
 */
public final class GlobMatcher {
  private final String glob;
  private final boolean ignoreCase;
  // The literal runs between the stars; '?' in them is the one-character wildcard.
  private final String[] segments;
  private final boolean leadingStar;
  private final boolean trailingStar;
  private final int minLength;
  private final boolean hasWildcardChar;
  private final boolean globHasLineTerminator;
  private volatile Pattern fallback;

  private GlobMatcher(final String glob, final boolean ignoreCase) {
    this.glob = glob;
    this.ignoreCase = ignoreCase;
    final List<String> parts = new ArrayList<>();
    final StringBuilder segment = new StringBuilder();
    boolean question = false;
    boolean terminator = false;
    for (int i = 0; i < glob.length(); i++) {
      final char c = glob.charAt(i);
      if (c == '*') {
        if (segment.length() > 0) {
          parts.add(segment.toString());
          segment.setLength(0);
        }
        continue;
      }
      question |= c == '?';
      terminator |= isLineTerminator(c);
      segment.append(ignoreCase ? lower(c) : c);
    }
    if (segment.length() > 0) {
      parts.add(segment.toString());
    }
    this.segments = parts.toArray(new String[0]);
    this.leadingStar = !glob.isEmpty() && glob.charAt(0) == '*';
    this.trailingStar = !glob.isEmpty() && glob.charAt(glob.length() - 1) == '*';
    int min = 0;
    for (final String part : segments) {
      min += part.length();
    }
    this.minLength = min;
    this.hasWildcardChar = question;
    this.globHasLineTerminator = terminator;
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
    if (globHasLineTerminator) {
      return fallback().matcher(text).matches();
    }
    final int n = text.length();
    if (n < minLength) {
      return false; // fewer chars than the literals need, so fewer code points too
    }
    // The whole text must match, and neither wildcard matches a line terminator, so one in the
    // text means no match (the glob has none). A surrogate pair facing a '?' is one character,
    // not two, so that case is left to the regex.
    for (int i = 0; i < n; i++) {
      final char c = text.charAt(i);
      if (isLineTerminator(c)) {
        return false;
      }
      if (hasWildcardChar && Character.isSurrogate(c)) {
        return fallback().matcher(text).matches();
      }
    }
    final int last = segments.length - 1;
    if (last < 0) {
      return leadingStar || n == 0; // "*" (or "**"...) matches anything; "" matches only ""
    }
    int pos = 0;
    int from = 0;
    if (!leadingStar) {
      if (last == 0 && !trailingStar) {
        return n == segments[0].length() && regionMatches(segments[0], text, 0);
      }
      if (!regionMatches(segments[0], text, 0)) {
        return false;
      }
      pos = segments[0].length();
      from = 1;
    }
    final int lastAt = trailingStar ? -1 : n - segments[last].length();
    final int middleEnd = trailingStar ? last : last - 1;
    for (int k = from; k <= middleEnd; k++) {
      final int at = find(segments[k], text, pos, trailingStar ? n : lastAt);
      if (at < 0) {
        return false;
      }
      pos = at + segments[k].length();
    }
    if (!trailingStar && from <= last) {
      return lastAt >= pos && regionMatches(segments[last], text, lastAt);
    }
    return true;
  }

  /** The leftmost position in {@code [from, limitEnd - segment.length()]} where it matches. */
  private int find(final String segment, final CharSequence text, final int from, final int end) {
    final int limit = end - segment.length();
    for (int at = from; at <= limit; at++) {
      if (regionMatches(segment, text, at)) {
        return at;
      }
    }
    return -1;
  }

  private boolean regionMatches(final String segment, final CharSequence text, final int at) {
    if (at < 0 || at + segment.length() > text.length()) {
      return false;
    }
    for (int j = 0; j < segment.length(); j++) {
      final char p = segment.charAt(j);
      if (p == '?') {
        continue; // checked above: not a line terminator, and not half a surrogate pair
      }
      final char c = text.charAt(at + j);
      if (p != (ignoreCase ? lower(c) : c)) {
        return false;
      }
    }
    return true;
  }

  private Pattern fallback() {
    Pattern pattern = fallback;
    if (pattern == null) {
      final StringBuilder regex = new StringBuilder(glob.length() + 16).append('^');
      for (int i = 0; i < glob.length(); i++) {
        final char c = glob.charAt(i);
        if (c == '*') {
          regex.append(".*");
        } else if (c == '?') {
          regex.append('.');
        } else {
          regex.append(Pattern.quote(String.valueOf(c)));
        }
      }
      regex.append('$');
      pattern = Pattern.compile(regex.toString(), ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
      fallback = pattern;
    }
    return pattern;
  }

  private static char lower(final char c) {
    return c >= 'A' && c <= 'Z' ? (char) (c + 32) : c;
  }

  /** The line terminators {@code java.util.regex}'s {@code .} doesn't match, without UNIX_LINES. */
  private static boolean isLineTerminator(final char c) {
    return c == '\n' || c == '\r' || c == '\u0085' || c == ' ' || c == ' ';
  }

  @Override
  public String toString() {
    return glob;
  }
}
