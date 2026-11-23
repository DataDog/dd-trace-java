package com.datadog.openfeature;

/**
 * Controls per-subject exclusion from experimentation.
 *
 * @see Provider.Options#experimentationOptOutMode(ExperimentationOptOutMode)
 */
public enum ExperimentationOptOutMode {
  /** Reads DD_FEATURE_FLAGS_EXPERIMENTATION_OPT_OUT_MODE, which defaults to disabled. */
  DEFAULT,
  /** Ignores dd_experimentation_opt_out_at, overriding the environment setting. */
  DISABLED,
  /**
   * Skips exposure-logging allocations when dd_experimentation_opt_out_at is a timestamp String
   * accepted by {@link java.time.format.DateTimeFormatter#ISO_OFFSET_DATE_TIME}. Applies
   * immediately, including to experiments already in progress.
   */
  IMMEDIATE,
  /**
   * Skips exposure-logging allocations activated at or after dd_experimentation_opt_out_at. Unknown
   * activation is skipped. Older allocations continue normally, including exposures. This is an
   * allocation activation cutoff, not a record of prior subject enrollment.
   */
  FUTURE_EXPERIMENTS_ONLY
}
