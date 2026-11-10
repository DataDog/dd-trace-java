package com.datadog.openfeature.internal.ufc;

/** Optional evaluator controls carried by UFC. */
public final class EvaluatorParams {
  public final Object maxDependencyDepth;
  public final boolean maxDependencyDepthPresent;
  public final String validationError;

  public EvaluatorParams(final Object maxDependencyDepth) {
    this(maxDependencyDepth, maxDependencyDepth != null);
  }

  public EvaluatorParams(final Object maxDependencyDepth, final boolean maxDependencyDepthPresent) {
    this(maxDependencyDepth, maxDependencyDepthPresent, null);
  }

  EvaluatorParams(
      final Object maxDependencyDepth,
      final boolean maxDependencyDepthPresent,
      final String validationError) {
    this.maxDependencyDepth = maxDependencyDepth;
    this.maxDependencyDepthPresent = maxDependencyDepthPresent;
    this.validationError = validationError;
  }
}
