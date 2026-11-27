package datadog.trace.core.tagprocessor;

import static datadog.trace.core.tagprocessor.QueryObfuscator.DEFAULT_OBFUSCATION_PATTERN;
import static datadog.trace.core.tagprocessor.QueryObfuscator.mayMatchDefaultPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.re2j.Pattern;
import java.util.Arrays;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link QueryObfuscator#mayMatchDefaultPattern} against re2j running the default pattern. Kept
 * apart from {@code QueryObfuscatorTest}, which covers how the obfuscator rewrites span tags, since
 * these tests check one helper against the pattern itself and don't touch spans or tags.
 */
class QueryObfuscatorDefaultPatternTest {
  private static final Pattern DEFAULT = Pattern.compile(DEFAULT_OBFUSCATION_PATTERN);

  /**
   * Fails on any edit to the default pattern. The check, and the pieces the differential test below
   * builds queries from, list the literals every match contains, so a new alternative needs adding
   * to both. Once they're updated, set the new hash and length here.
   */
  @Test
  void theDefaultPatternIsTheOneTheCheckWasWrittenFor() {
    assertEquals(841, DEFAULT_OBFUSCATION_PATTERN.length());
    assertEquals(1426552850, DEFAULT_OBFUSCATION_PATTERN.hashCode());
  }

  /** The check folds case as it believes re2j's {@code (?i)} does; this pins that belief. */
  @Test
  void onlyKelvinSignAndLongSFoldOntoAsciiLetters() {
    final Pattern letter = Pattern.compile("(?i)[a-z]");
    final Set<String> nonAscii = new TreeSet<>();
    for (int c = 0x80; c <= 0xFFFF; c++) {
      if (!Character.isSurrogate((char) c) && letter.matcher(String.valueOf((char) c)).matches()) {
        nonAscii.add(String.format("U+%04X", c));
      }
    }
    assertEquals(new TreeSet<>(Arrays.asList("U+017F", "U+212A")), nonAscii);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "password=hunter2",
        "PWD=x",
        "new_pword=x",
        "passphrase=x",
        "client_secret=x",
        "api-key=x",
        "token=x",
        "consumer_id=x",
        "signature=x",
        "authorization=x",
        "%22pass%22%3A%22x%22",
        "h=Bearer abc.def",
        "x=token:abcdefghijklm",
        "x=ghp_0123456789abcdefghijklmnopqrstuvwxyz",
        "x=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.sig",
        "apiKey=x",
        "ſecret=x",
        "paſſ=x"
      })
  void everyDefaultPatternHitPassesTheCheck(final String query) {
    assertTrue(DEFAULT.matcher(query).find(), "fixture should hit the pattern");
    assertTrue(mayMatchDefaultPattern(query));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "page=1", "q=shoes&size=10&color=red", "sort=asc&offset=200", "id=12345"})
  void ordinaryQueriesFailTheCheck(final String query) {
    assertFalse(mayMatchDefaultPattern(query));
  }

  /**
   * Builds queries from the pattern's own literals, near misses, separators and noise, and checks
   * the one property the obfuscator relies on: whenever the check says no, re2j finds no match.
   */
  @Test
  void neverRejectsAQueryTheDefaultPatternMatches() {
    final String[] pieces = {
      "pw",
      "pass",
      "passwd",
      "password",
      "phrase",
      "secret",
      "key",
      "token",
      "consumer",
      "sign",
      "auth",
      "bearer ",
      "bearer%20",
      "ghp_",
      "gho_",
      "ghs_",
      "ghu_",
      "eyJ",
      "eyI",
      "eyK",
      "eyL",
      "-----BEGIN RSA PRIVATE KEY-----",
      "-----END RSA PRIVATE KEY-----",
      "ssh-rsa ",
      "ssh-dss ",
      "ecdsa-sha2-nistp256 ",
      "old_",
      "new-",
      "api_",
      "app",
      "lication",
      "_id",
      "=",
      "%3D",
      "%3d",
      "\"",
      "%22",
      ":",
      "%3A",
      " ",
      "%20",
      "&",
      ".",
      "_",
      "-",
      "/",
      "+",
      "%2F",
      "%0A",
      "\n",
      "page",
      "q",
      "value",
      "abc",
      "0123456789",
      "K",
      "ſ",
      "ß",
      "İ",
      "ı"
    };
    final Random random = new Random(13597);
    int hits = 0;
    int skipped = 0;
    for (int t = 0; t < 200_000; t++) {
      final StringBuilder query = new StringBuilder();
      final int parts = 1 + random.nextInt(8);
      for (int p = 0; p < parts; p++) {
        if (random.nextInt(6) == 0) {
          query.append((char) (0x20 + random.nextInt(0x5F)));
        } else {
          query.append(flipCase(pieces[random.nextInt(pieces.length)], random));
        }
      }
      if (random.nextInt(4) == 0) {
        // long base64-ish run, for the SSH key alternative's {100,}
        for (int i = 0; i < 100 + random.nextInt(20); i++) {
          query.append("abcXYZ019+/".charAt(random.nextInt(11)));
        }
      }
      final String text = query.toString();
      final boolean match = DEFAULT.matcher(text).find();
      final boolean check = mayMatchDefaultPattern(text);
      if (match) {
        hits++;
        assertTrue(check, () -> "the check rejected a match: " + text);
      } else if (!check) {
        skipped++;
      }
    }
    // the generator must exercise both sides, or the property says little
    assertTrue(hits > 10_000, "hits: " + hits);
    assertTrue(skipped > 10_000, "skipped: " + skipped);
  }

  private static String flipCase(final String piece, final Random random) {
    if (random.nextBoolean()) {
      return piece;
    }
    final StringBuilder flipped = new StringBuilder(piece.length());
    for (int i = 0; i < piece.length(); i++) {
      final char c = piece.charAt(i);
      flipped.append(random.nextBoolean() ? Character.toUpperCase(c) : c);
    }
    return flipped.toString();
  }
}
