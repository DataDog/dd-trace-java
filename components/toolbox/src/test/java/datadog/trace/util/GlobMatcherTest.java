package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class GlobMatcherTest {

  @TableTest({
    "glob           | text                | matches",
    "'web-*'        | 'web-checkout'      | true   ",
    "'web-*'        | 'api-checkout'      | false  ",
    "'*.checkout.*' | 'svc.checkout.v2'   | true   ",
    "'*.checkout.*' | 'svc.checkout'      | false  ",
    "'GET /api/*'   | 'GET /api/orders/1' | true   ",
    "'a?c'          | 'abc'               | true   ",
    "'a?c'          | 'ac'                | false  ",
    "'a*b*c'        | 'aXXbYYc'           | true   ",
    "'a*b*c'        | 'aXXcYYb'           | false  ",
    "'*'            | ''                  | true   ",
    "''             | ''                  | true   ",
    "''             | 'a'                 | false  ",
    "'a+b'          | 'a+b'               | true   ",
    "'a+b'          | 'aab'               | false  ",
    "'a.b'          | 'axb'               | false  "
  })
  void matchesWholeText(final String glob, final String text, final boolean matches) {
    assertEquals(matches, GlobMatcher.compile(glob).matches(text), glob + " / " + text);
  }

  @Test
  void ignoresTheCaseOfAsciiLettersOnly() {
    final GlobMatcher matcher = GlobMatcher.compileIgnoringCase("Web-*-PROD");
    assertTrue(matcher.matches("web-checkout-prod"));
    assertTrue(matcher.matches("WEB-x-Prod"));
    assertFalse(GlobMatcher.compile("Web-*").matches("web-x"));
    // Without UNICODE_CASE, CASE_INSENSITIVE doesn't fold é and É, or K and the Kelvin sign.
    assertFalse(GlobMatcher.compileIgnoringCase("café").matches("CAFÉ"));
    assertFalse(GlobMatcher.compileIgnoringCase("k").matches("K"));
  }

  @Test
  void wildcardsDoNotMatchLineTerminators() {
    for (final String terminator : new String[] {"\n", "\r", "\u0085", " ", " "}) {
      assertFalse(GlobMatcher.compile("a*b").matches("a" + terminator + "b"), terminator);
      assertFalse(GlobMatcher.compile("a?b").matches("a" + terminator + "b"), terminator);
    }
    // A literal line terminator in the glob matches itself.
    assertTrue(GlobMatcher.compile("a\nb*").matches("a\nbc"));
  }

  @Test
  void aQuestionMarkMatchesOneCodePoint() {
    assertTrue(GlobMatcher.compile("a?b").matches("a😀b"));
    assertFalse(GlobMatcher.compile("a??b").matches("a😀b"));
    assertTrue(GlobMatcher.compile("a*b").matches("a😀b"));
  }

  @Test
  void keepsItsGlob() {
    assertEquals("a*", GlobMatcher.compile("a*").glob());
    assertEquals("a*", GlobMatcher.compile("a*").toString());
  }

  /** Every result agrees with the glob's regex on random globs and texts. */
  @Test
  void agreesWithTheRegexOnRandomGlobsAndTexts() {
    final Random random = new Random(7);
    final String alphabet = "aAbB.-+$\\(*?éÉ";
    int checked = 0;
    for (int g = 0; g < 3_000; g++) {
      final String glob = randomString(alphabet, random, 7);
      for (final boolean ignoreCase : new boolean[] {false, true}) {
        final GlobMatcher matcher =
            ignoreCase ? GlobMatcher.compileIgnoringCase(glob) : GlobMatcher.compile(glob);
        final Pattern reference = regex(glob, ignoreCase);
        for (final String text : texts(glob, alphabet, random)) {
          assertEquals(
              reference.matcher(text).matches(),
              matcher.matches(text),
              () -> "/" + glob + "/" + (ignoreCase ? "i" : "") + " on \"" + text + "\"");
          checked++;
        }
      }
    }
    assertTrue(checked > 200_000, "checked " + checked);
  }

  private static List<String> texts(final String glob, final String alphabet, final Random r) {
    final List<String> texts = new ArrayList<>();
    final String alpha = alphabet.replace("*", "").replace("?", "") + "xyz";
    for (int k = 0; k < 12; k++) {
      final String match = expand(glob, alpha, r);
      texts.add(match);
      texts.add(flipCase(match, r));
      texts.add(mutate(match, alpha, r));
      texts.add(randomString(alpha, r, 8));
    }
    for (final String extra : new String[] {"\n", "\r", "\u0085", " ", " ", "😀", "K", "é"}) {
      final String match = expand(glob, alpha, r);
      final int at = match.isEmpty() ? 0 : r.nextInt(match.length() + 1);
      texts.add(match.substring(0, at) + extra + match.substring(at));
    }
    texts.add("");
    return texts;
  }

  /** A text the glob matches: each star a random run, each question mark a random char. */
  private static String expand(final String glob, final String alpha, final Random r) {
    final StringBuilder s = new StringBuilder();
    for (final char c : glob.toCharArray()) {
      if (c == '*') {
        s.append(randomString(alpha, r, 3));
      } else if (c == '?') {
        s.append(alpha.charAt(r.nextInt(alpha.length())));
      } else {
        s.append(c);
      }
    }
    return s.toString();
  }

  private static String flipCase(final String s, final Random r) {
    final char[] chars = s.toCharArray();
    for (int i = 0; i < chars.length; i++) {
      if (r.nextBoolean()) {
        chars[i] =
            Character.isUpperCase(chars[i])
                ? Character.toLowerCase(chars[i])
                : Character.toUpperCase(chars[i]);
      }
    }
    return new String(chars);
  }

  private static String mutate(final String s, final String alpha, final Random r) {
    if (s.isEmpty()) {
      return String.valueOf(alpha.charAt(r.nextInt(alpha.length())));
    }
    final int at = r.nextInt(s.length());
    final char c = alpha.charAt(r.nextInt(alpha.length()));
    switch (r.nextInt(3)) {
      case 0:
        return s.substring(0, at) + s.substring(at + 1);
      case 1:
        return s.substring(0, at) + c + s.substring(at);
      default:
        return s.substring(0, at) + c + s.substring(at + 1);
    }
  }

  private static String randomString(final String alphabet, final Random r, final int max) {
    final StringBuilder s = new StringBuilder();
    final int length = r.nextInt(max + 1);
    for (int i = 0; i < length; i++) {
      s.append(alphabet.charAt(r.nextInt(alphabet.length())));
    }
    return s.toString();
  }

  /** The glob as a regex: star to .*, question mark to ., every other character quoted. */
  private static Pattern regex(final String glob, final boolean ignoreCase) {
    final StringBuilder regex = new StringBuilder("^");
    for (final char c : glob.toCharArray()) {
      if (c == '*') {
        regex.append(".*");
      } else if (c == '?') {
        regex.append('.');
      } else {
        regex.append(Pattern.quote(String.valueOf(c)));
      }
    }
    return Pattern.compile(regex.append('$').toString(), ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
  }
}
