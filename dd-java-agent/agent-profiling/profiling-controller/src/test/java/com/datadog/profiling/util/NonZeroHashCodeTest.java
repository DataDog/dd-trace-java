package com.datadog.profiling.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NonZeroHashCodeTest {

  @Test
  void hashNull() {
    assertNotEquals(0, NonZeroHashCode.hash((Object) null));
  }

  @Test
  void hashNonNull() {
    assertNotEquals(0, NonZeroHashCode.hash(new Object()));
  }
}
