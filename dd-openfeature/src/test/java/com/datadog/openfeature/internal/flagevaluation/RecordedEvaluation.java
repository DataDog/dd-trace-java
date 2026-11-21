package com.datadog.openfeature.internal.flagevaluation;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** One evaluation as passed to {@link FlagEvaluationWriter#record}, for tests. */
public final class RecordedEvaluation {
  public final String flagKey;
  public final String variant;
  public final String allocationKey;
  public final String targetingKey;
  public final String errorMessage;
  public final long evalTimeMs;
  public final boolean observeFullEvaluationData;
  public final Map<String, Object> attrs;
  public final Map<String, Object> features;

  public RecordedEvaluation(
      final String flagKey,
      final String variant,
      final String allocationKey,
      final String targetingKey,
      final String errorMessage,
      final long evalTimeMs,
      final boolean observeFullEvaluationData,
      final Map<String, Object> attrs,
      final Map<String, Object> features) {
    this.flagKey = flagKey;
    this.variant = variant;
    this.allocationKey = allocationKey;
    this.targetingKey = targetingKey;
    this.errorMessage = errorMessage;
    this.evalTimeMs = evalTimeMs;
    this.observeFullEvaluationData = observeFullEvaluationData;
    this.attrs = attrs;
    this.features = features;
  }

  /** Records this evaluation through {@code writer}. */
  public void recordInto(final FlagEvaluationWriter writer) {
    writer.record(
        flagKey,
        variant,
        allocationKey,
        targetingKey,
        errorMessage,
        evalTimeMs,
        observeFullEvaluationData,
        attrs,
        features);
  }

  /** Records this evaluation into {@code aggregator}. */
  void recordInto(final ConcurrentFlagAggregator aggregator) {
    aggregator.record(
        flagKey,
        variant,
        allocationKey,
        targetingKey,
        errorMessage,
        evalTimeMs,
        observeFullEvaluationData,
        attrs,
        features);
  }

  /** A writer keeping what it is given. */
  public static class Recorder implements FlagEvaluationWriter {
    public final List<RecordedEvaluation> evaluations = new CopyOnWriteArrayList<>();
    public final List<String> truncations = new CopyOnWriteArrayList<>();
    private final Consumer<RecordedEvaluation> listener;

    public Recorder() {
      this(evaluation -> {});
    }

    /** A recorder also passing each evaluation to {@code listener}. */
    public Recorder(final Consumer<RecordedEvaluation> listener) {
      this.listener = listener;
    }

    @Override
    public void countContextTruncated(final String reason) {
      truncations.add(reason);
    }

    @Override
    public void record(
        final String flagKey,
        final String variant,
        final String allocationKey,
        final String targetingKey,
        final String errorMessage,
        final long evalTimeMs,
        final boolean observeFullEvaluationData,
        final Map<String, Object> attrs,
        final Map<String, Object> features) {
      final RecordedEvaluation evaluation =
          new RecordedEvaluation(
              flagKey,
              variant,
              allocationKey,
              targetingKey,
              errorMessage,
              evalTimeMs,
              observeFullEvaluationData,
              attrs,
              features);
      evaluations.add(evaluation);
      listener.accept(evaluation);
    }
  }
}
