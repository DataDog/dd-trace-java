package com.datadog.openfeature.internal.connector;

import javax.annotation.Nullable;

/** Reflects flag evaluations onto the active local root span. */
public interface SpanEnricher {
  /** The enricher discarding all evaluations. */
  SpanEnricher NOOP =
      new SpanEnricher() {
        @Override
        public void serialId(
            final int serialId, final boolean doLog, @Nullable final String targetingKey) {}

        @Override
        public void runtimeDefault(final String flagKey, @Nullable final Object defaultValue) {}
      };

  /**
   * Records an evaluation that resolved to a split with a serial id.
   *
   * @param serialId the split serial id.
   * @param doLog whether the allocation logs exposures, to also record the subject.
   * @param targetingKey the optional targeting key of the subject.
   */
  void serialId(int serialId, boolean doLog, @Nullable String targetingKey);

  /**
   * Records an evaluation that resolved to its runtime default value.
   *
   * @param flagKey the flag key.
   * @param defaultValue the default value, unwrapped to a native Java type (map, list or scalar).
   */
  void runtimeDefault(String flagKey, @Nullable Object defaultValue);
}
