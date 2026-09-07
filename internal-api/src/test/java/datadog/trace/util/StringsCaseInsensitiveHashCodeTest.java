package datadog.trace.util;

import static datadog.trace.util.Strings.caseInsensitiveHashCode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class StringsCaseInsensitiveHashCodeTest {

  @Test
  void equalIgnoringCaseProducesEqualHash() {
    assertEquals(caseInsensitiveHashCode("Content-Type"), caseInsensitiveHashCode("content-type"));
    assertEquals(caseInsensitiveHashCode("Content-Type"), caseInsensitiveHashCode("CONTENT-TYPE"));
    assertEquals(caseInsensitiveHashCode("Content-Type"), caseInsensitiveHashCode("cOnTeNt-TyPe"));
  }

  @Test
  void emptyStringHashesToZero() {
    // Matches String.hashCode("") == 0.
    assertEquals(0, caseInsensitiveHashCode(""));
  }

  @Test
  void distinctContentHashesDiffer() {
    // Not a guarantee in general, but these representative keys must not collide.
    assertNotEquals(caseInsensitiveHashCode("foo"), caseInsensitiveHashCode("bar"));
    assertNotEquals(caseInsensitiveHashCode("Accept"), caseInsensitiveHashCode("Host"));
  }

  @Test
  void staysConsistentWithEqualsIgnoreCase() {
    // For any pair, equalsIgnoreCase => equal hash. (The converse — unequal hash implies not
    // equalsIgnoreCase — is what a table relies on to never miss a present key.)
    String[] samples = {
      "Accept",
      "accept",
      "ACCEPT",
      "Accept-Encoding",
      "accept-encoding",
      "X-Forwarded-For",
      "x-forwarded-for",
      "Host",
      "host"
    };
    for (String a : samples) {
      for (String b : samples) {
        if (a.equalsIgnoreCase(b)) {
          assertEquals(
              caseInsensitiveHashCode(a),
              caseInsensitiveHashCode(b),
              () -> "hash mismatch for equalIgnoreCase pair");
        }
      }
    }
  }

  /**
   * A supplementary-plane pair that {@code equalsIgnoreCase} unifies on JDK 9+ (code-point-based
   * folding) but a per-{@code char} hash fold kept distinct -- the bug this fold fixes. Folding by
   * code point unifies the hash too, so this holds on every JDK regardless of what {@code
   * equalsIgnoreCase} itself does with the pair on that JDK (see {@link
   * #staysConsistentAcrossAllDefinedCodePoints} for the guarded, cross-JDK-safe version of this
   * check).
   */
  @Test
  void foldsSupplementaryCasePairTogether() {
    String s1 = new String(Character.toChars(0x10400)); // DESERET CAPITAL LETTER LONG I
    String s2 = new String(Character.toChars(0x10428)); // DESERET SMALL LETTER LONG I
    assertEquals(caseInsensitiveHashCode(s1), caseInsensitiveHashCode(s2));
  }

  /**
   * Exhaustive sweep guarded by {@code equalsIgnoreCase} itself, so it stays portable across JDKs:
   * on JDK 8 (no code-point reconstruction) the supplementary pairs above simply never enter the
   * guard, and the sweep still passes.
   */
  @Test
  void staysConsistentAcrossAllDefinedCodePoints() {
    for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
      if (!Character.isDefined(cp) || Character.isSurrogate((char) cp)) continue;
      String s = new String(Character.toChars(cp));
      for (int variant :
          new int[] {
            Character.toUpperCase(cp), Character.toLowerCase(cp), Character.toTitleCase(cp)
          }) {
        String t = new String(Character.toChars(variant));
        if (s.equalsIgnoreCase(t)) {
          final String fs = s;
          final String ft = t;
          assertEquals(
              caseInsensitiveHashCode(s),
              caseInsensitiveHashCode(t),
              () ->
                  "hash mismatch for equalsIgnoreCase pair U+"
                      + Integer.toHexString(fs.codePointAt(0))
                      + " / U+"
                      + Integer.toHexString(ft.codePointAt(0)));
        }
      }
    }
  }
}
