package com.datadog.openfeature.internal.evaluation;

import java.util.Map;

/** Registers evaluation features from tests outside this package. */
public final class EvaluationFeatureHandlesForTest {
  private EvaluationFeatureHandlesForTest() {}

  public static long register(final Map<String, Object> features) {
    return EvaluationFeatureHandles.register(EvaluationFeatureHandles.prepared(features));
  }
}
