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
}
