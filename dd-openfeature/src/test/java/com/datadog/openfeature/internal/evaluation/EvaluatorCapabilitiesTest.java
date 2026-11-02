package com.datadog.openfeature.internal.evaluation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EvaluatorCapabilitiesTest {
  @Test
  void doesNotAdvertiseUnsupportedDependentFlags() {
    assertTrue(EvaluatorCapabilities.supported().isEmpty());
  }
}
