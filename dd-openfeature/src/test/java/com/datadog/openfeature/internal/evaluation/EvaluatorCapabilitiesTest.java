package com.datadog.openfeature.internal.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EvaluatorCapabilitiesTest {
  @Test
  void advertisesDependentFlags() {
    assertEquals(
        java.util.Collections.singleton(EvaluatorCapabilities.DEPENDENT_FLAG_VARIANT_KEY_V1),
        EvaluatorCapabilities.supported());
  }
}
