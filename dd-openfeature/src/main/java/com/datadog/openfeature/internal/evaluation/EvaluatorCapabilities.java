package com.datadog.openfeature.internal.evaluation;

import static java.util.Collections.singleton;

import java.util.Set;

/** Capabilities supported by both local evaluation and exposure reporting. */
public final class EvaluatorCapabilities {
  public static final String DEPENDENT_FLAG_VARIANT_KEY_V1 = "dependent-flag-variant-key-v1";

  private EvaluatorCapabilities() {}

  /** Returns the capabilities implemented by evaluation and exposure reporting. */
  public static Set<String> supported() {
    return singleton(DEPENDENT_FLAG_VARIANT_KEY_V1);
  }
}
