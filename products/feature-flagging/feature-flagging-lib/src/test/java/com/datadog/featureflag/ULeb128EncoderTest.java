package com.datadog.featureflag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ULeb128EncoderTest {

  @Test
  void hashTargetingKeyMatchesCanonicalPiiVector() {
    // Canonical vector shared across all SDK implementations — see system-tests PR #7316.
    assertEquals(
        "b4698f9b6d186781fa8dc59e533578fa2d8379a46b1cf6db85cda6aa9c99e51b",
        ULeb128Encoder.hashTargetingKey("jane.doe@datadoghq.com"));
  }

  @Test
  void hashTargetingKeyPreservesWhitespaceExactly() {
    assertNotEquals(
        ULeb128Encoder.hashTargetingKey("jane.doe@datadoghq.com"),
        ULeb128Encoder.hashTargetingKey(" jane.doe@datadoghq.com "));
  }

  @Test
  void hashTargetingKeyPreservesCaseExactly() {
    assertNotEquals(
        ULeb128Encoder.hashTargetingKey("jane.doe@datadoghq.com"),
        ULeb128Encoder.hashTargetingKey("JANE.DOE@DATADOGHQ.COM"));
  }

  @Test
  void hashTargetingKeyIsLowercase64CharHex() {
    final String hash = ULeb128Encoder.hashTargetingKey("some-arbitrary-key");
    assertEquals(64, hash.length());
    assertTrue(hash.matches("[0-9a-f]{64}"));
  }
}
