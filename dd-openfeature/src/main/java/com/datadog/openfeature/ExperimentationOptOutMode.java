package com.datadog.openfeature;

/** Controls per-subject exclusion from experimentation. */
public enum ExperimentationOptOutMode {
  /** Reads DD_FEATURE_FLAGS_EXPERIMENTATION_OPT_OUT_MODE, which defaults to disabled. */
  DEFAULT,
  /** Ignores dd_experimentation_opt_out_at, overriding the environment setting. */
  DISABLED,
  /**
   * Skips exposure-logging allocations when dd_experimentation_opt_out_at is an RFC3339 timestamp.
   * Applies immediately, including to experiments already in progress.
   */
  IMMEDIATE
}
