package com.datadog.openfeature.internal.evaluation;

import com.datadog.openfeature.ExperimentationOptOutMode;
import com.datadog.openfeature.internal.config.Settings;
import com.datadog.openfeature.internal.ufc.Allocation;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.Value;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Validates the subject's opt-out timestamp before allocation selection. */
public final class ExperimentationOptOut {
  public static final String ATTRIBUTE = "dd_experimentation_opt_out_at";
  public static final String INVALID_TIMESTAMP =
      ATTRIBUTE
          + " must be an RFC3339 timestamp string; supply a valid timestamp to opt out,"
          + " or omit the attribute to permit experimentation";

  private ExperimentationOptOut() {}

  /** Resolves the provider option before reading central configuration. */
  public static ExperimentationOptOutMode resolve(
      final ExperimentationOptOutMode override, final Settings settings) {
    Objects.requireNonNull(override, "experimentationOptOutMode");
    if (override != ExperimentationOptOutMode.DEFAULT) {
      return override;
    }
    final String configured = settings.experimentationOptOutMode();
    switch (configured.trim().toLowerCase(Locale.ROOT)) {
      case "":
      case "disabled":
        return ExperimentationOptOutMode.DISABLED;
      case "immediate":
        return ExperimentationOptOutMode.IMMEDIATE;
      case "future_experiments_only":
        return ExperimentationOptOutMode.FUTURE_EXPERIMENTS_ONLY;
      default:
        throw new IllegalArgumentException(
            "DD_FEATURE_FLAGS_EXPERIMENTATION_OPT_OUT_MODE must be disabled, immediate, or future_experiments_only");
    }
  }

  /** Parses the cutoff once per evaluation; null means no exclusion. */
  static Instant cutoff(final ExperimentationOptOutMode mode, final EvaluationContext context) {
    if ((mode != ExperimentationOptOutMode.IMMEDIATE
            && mode != ExperimentationOptOutMode.FUTURE_EXPERIMENTS_ONLY)
        || !context.keySet().contains(ATTRIBUTE)) {
      return null;
    }
    final Value value = context.getValue(ATTRIBUTE);
    if (value != null && value.isString()) {
      try {
        // Use the allocation startAt/endAt parser's timestamp contract.
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(value.asString(), Instant::from);
      } catch (final DateTimeException e) {
        // Do not expose the supplied context value in the diagnostic.
      }
    }
    throw new IllegalArgumentException(INVALID_TIMESTAMP);
  }

  /** Checks prepared activation states before selecting an allocation. */
  static boolean hasInvalidActivation(final List<Allocation> allocations) {
    if (allocations == null) {
      return false;
    }
    for (final Allocation allocation : allocations) {
      if (allocation != null && allocation.invalidRandomizationActivation) {
        return true;
      }
    }
    return false;
  }

  /** Excludes before targeting; unknown activation does not qualify as older. */
  static boolean excludes(
      final ExperimentationOptOutMode mode, final Instant cutoff, final Allocation allocation) {
    if (cutoff == null) {
      return false;
    }
    if (!Boolean.TRUE.equals(allocation.doLog)) {
      return false;
    }
    return mode == ExperimentationOptOutMode.IMMEDIATE
        || allocation.randomizationActivatedAt == null
        || !allocation.randomizationActivatedAt.isBefore(cutoff);
  }
}
