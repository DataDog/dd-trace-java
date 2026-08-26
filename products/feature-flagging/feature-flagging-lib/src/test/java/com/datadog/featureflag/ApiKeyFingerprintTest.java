package com.datadog.featureflag;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ApiKeyFingerprintTest {
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "''|rijn_RZwTDmWjELXeEmMEb0eIIegKayGGUPNsuJweEPhlXi5",
        "padding-171|rijn_053ybBRXypQt9AC6UIlqH1YCFYSV1rQl8HCDIcBZs3D",
        "!@#$%^𐍈한€हИ£|rijn_eFLHeyLxwaiNs2hY16pjkjNjVSHWRgf2rlveKc8YA1K",
        "secret|rijn_amLaG4Pd6h6t9VtJna81k744P1DYxGHzIJ6ECO3OOMj"
      })
  void matchesCanonicalCliffordV1Vectors(final String apiKey, final String expected) {
    assertEquals(expected, ApiKeyFingerprint.create(apiKey));
    assertEquals(48, ApiKeyFingerprint.create(apiKey).length());
  }
}
