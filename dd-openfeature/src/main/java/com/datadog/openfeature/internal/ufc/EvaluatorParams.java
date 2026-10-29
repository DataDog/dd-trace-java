package com.datadog.openfeature.internal.ufc;

/** Optional evaluator controls carried by UFC. */
public final class EvaluatorParams {
  public final Object maxDependencyDepth;
  public final boolean maxDependencyDepthPresent;

  public EvaluatorParams(final Object maxDependencyDepth) {
    this(maxDependencyDepth, maxDependencyDepth != null);
  }

  public EvaluatorParams(final Object maxDependencyDepth, final boolean maxDependencyDepthPresent) {
    this.maxDependencyDepth = maxDependencyDepth;
    this.maxDependencyDepthPresent = maxDependencyDepthPresent;
  }
}
