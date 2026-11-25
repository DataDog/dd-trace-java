package datadog.trace.bootstrap.jit;

import static datadog.trace.bootstrap.jit.Outcomes.assertSameOutcome;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Differential tests: each fused helper must behave exactly like the chain it replaces. */
class StringFusionsTest {

  private static final String[] STRINGS = {
    "",
    "gzip",
    "GZip",
    "Content-Type",
    "CONTENT-TYPE",
    "ÀÉÎx",
    "àéîx",
    "straße",
    "STRASSE",
    "µs",
    "ÿes",
    "İstanbul",
    "istanbul",
    "ISTANBUL",
    "ı",
    "ΟΔΟΣ",
    "οδος",
    "𐐀",
    "\uD801",
    "a.JSON",
    "  padded \t",
    "x",
    null
  };

  private static final Object[] OTHERS = {
    "",
    "gzip",
    "content-type",
    "CONTENT-TYPE",
    "àéîx",
    "strasse",
    "STRASSE",
    "μ" + "S",
    "ŸES",
    "i̇stanbul",
    "istanbul",
    "ISTANBUL",
    "οδος",
    "𐐨",
    ".json",
    ".JSON",
    "a",
    "X",
    new StringBuilder("gzip"),
    null
  };

  private static final Locale[] LOCALES = {
    Locale.ROOT,
    Locale.ENGLISH,
    Locale.GERMANY,
    new Locale("tr", "TR"),
    new Locale("az"),
    new Locale("lt"),
    new Locale("el"),
    null
  };

  private static final String[] TRIMMED = {
    "", " ", "\t\n", "\u0000", " a ", "a", " ", " x ", " \u0001b\u001F ", null
  };

  private Locale saved;

  @BeforeEach
  void saveLocale() {
    saved = Locale.getDefault();
  }

  @AfterEach
  void restoreLocale() {
    Locale.setDefault(saved);
  }

  @Test
  void caseFusionsMatchJdk() {
    for (Locale defaultLocale : LOCALES) {
      if (null != defaultLocale) {
        Locale.setDefault(defaultLocale);
      }
      for (String s : STRINGS) {
        for (Object other : OTHERS) {
          String p = other instanceof String ? (String) other : null;
          assertSameOutcome(
              () -> s.toLowerCase().equals(other), () -> StringFusions.lowerEquals(s, other));
          assertSameOutcome(
              () -> s.toUpperCase().equals(other), () -> StringFusions.upperEquals(s, other));
          assertSameOutcome(
              () -> s.toLowerCase().startsWith(p), () -> StringFusions.lowerStartsWith(s, p));
          assertSameOutcome(
              () -> s.toUpperCase().startsWith(p), () -> StringFusions.upperStartsWith(s, p));
          assertSameOutcome(
              () -> s.toLowerCase().endsWith(p), () -> StringFusions.lowerEndsWith(s, p));
          assertSameOutcome(
              () -> s.toUpperCase().endsWith(p), () -> StringFusions.upperEndsWith(s, p));
          assertSameOutcome(() -> p.equals(s.toLowerCase()), () -> StringFusions.equalsLower(p, s));
          assertSameOutcome(() -> p.equals(s.toUpperCase()), () -> StringFusions.equalsUpper(p, s));
          for (Locale locale : LOCALES) {
            assertSameOutcome(
                () -> s.toLowerCase(locale).equals(other),
                () -> StringFusions.lowerEquals(s, locale, other));
            assertSameOutcome(
                () -> s.toUpperCase(locale).equals(other),
                () -> StringFusions.upperEquals(s, locale, other));
            assertSameOutcome(
                () -> s.toLowerCase(locale).startsWith(p),
                () -> StringFusions.lowerStartsWith(s, locale, p));
            assertSameOutcome(
                () -> s.toUpperCase(locale).startsWith(p),
                () -> StringFusions.upperStartsWith(s, locale, p));
            assertSameOutcome(
                () -> s.toLowerCase(locale).endsWith(p),
                () -> StringFusions.lowerEndsWith(s, locale, p));
            assertSameOutcome(
                () -> s.toUpperCase(locale).endsWith(p),
                () -> StringFusions.upperEndsWith(s, locale, p));
            assertSameOutcome(
                () -> p.equals(s.toLowerCase(locale)),
                () -> StringFusions.equalsLower(p, s, locale));
            assertSameOutcome(
                () -> p.equals(s.toUpperCase(locale)),
                () -> StringFusions.equalsUpper(p, s, locale));
          }
        }
      }
    }
  }

  @Test
  void substringFusionsMatchJdk() {
    for (String s : STRINGS) {
      int length = null == s ? 3 : s.length();
      for (Object other : OTHERS) {
        String p = other instanceof String ? (String) other : null;
        for (int begin = -1; begin <= length + 1; begin++) {
          int b = begin;
          assertSameOutcome(
              () -> s.substring(b).startsWith(p), () -> StringFusions.substringStartsWith(s, b, p));
          assertSameOutcome(
              () -> s.substring(b).endsWith(p), () -> StringFusions.substringEndsWith(s, b, p));
          assertSameOutcome(
              () -> s.substring(b).equals(other), () -> StringFusions.substringEquals(s, b, other));
          assertSameOutcome(
              () -> p.equals(s.substring(b)), () -> StringFusions.equalsSubstring(p, s, b));
          for (int end = -1; end <= length + 1; end++) {
            int e = end;
            assertSameOutcome(
                () -> s.substring(b, e).startsWith(p),
                () -> StringFusions.substringStartsWith(s, b, e, p));
            assertSameOutcome(
                () -> s.substring(b, e).endsWith(p),
                () -> StringFusions.substringEndsWith(s, b, e, p));
            assertSameOutcome(
                () -> s.substring(b, e).equals(other),
                () -> StringFusions.substringEquals(s, b, e, other));
            assertSameOutcome(
                () -> p.equals(s.substring(b, e)), () -> StringFusions.equalsSubstring(p, s, b, e));
          }
        }
      }
    }
  }

  @Test
  void trimFusionsMatchJdk() {
    for (String s : TRIMMED) {
      assertSameOutcome(() -> s.trim().isEmpty(), () -> StringFusions.trimIsEmpty(s));
      assertSameOutcome(() -> s.trim().length(), () -> StringFusions.trimLength(s));
    }
    for (String s : STRINGS) {
      assertSameOutcome(() -> s.trim().isEmpty(), () -> StringFusions.trimIsEmpty(s));
      assertSameOutcome(() -> s.trim().length(), () -> StringFusions.trimLength(s));
    }
  }
}
