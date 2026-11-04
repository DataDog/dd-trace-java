package com.datadog.openfeature.internal.evaluation;

import static java.util.Collections.emptySet;

import java.util.Set;

/** Capabilities supported by both local evaluation and exposure reporting. */
public final class EvaluatorCapabilities {
  public static final String DEPENDENT_FLAG_VARIANT_KEY_V1 = "dependent-flag-variant-key-v1";
  public static final String DEPENDENT_FLAGS_VARIANT_KEY_QUERY_PARAMETER =
      "dependent-flags-variant-key";
  public static final String DEPENDENT_FLAGS_VARIANT_KEY_QUERY_VERSION = "v1";

  private EvaluatorCapabilities() {}

  /** Keep empty until prerequisite evaluation, errors, and exposures are integration-tested. */
  public static Set<String> supported() {
    return emptySet();
  }
}
