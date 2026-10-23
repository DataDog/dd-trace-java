package datadog.trace.api.openfeature;

import datadog.trace.api.featureflag.config.FeatureFlaggingConfig;
import datadog.trace.api.featureflag.ufc.v1.Allocation;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.Value;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class ExperimentationOptOut {
  static final String ATTRIBUTE = "dd_experimentation_opt_out_at";
  static final String INVALID_TIMESTAMP =
      ATTRIBUTE
          + " must be an RFC3339 timestamp string; supply a valid timestamp to opt out,"
          + " or omit the attribute to permit experimentation";

  private ExperimentationOptOut() {}

  static ExperimentationOptOutMode resolve(final ExperimentationOptOutMode override) {
    Objects.requireNonNull(override, "experimentationOptOutMode");
    if (override != ExperimentationOptOutMode.DEFAULT) {
      return override;
    }
    final String configured = configuredMode();
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

  private static String configuredMode() {
    try {
      return ConfigProvider.getInstance()
          .getString(FeatureFlaggingConfig.FEATURE_FLAGS_EXPERIMENTATION_OPT_OUT_MODE, "disabled");
    } catch (final NoClassDefFoundError unavailableAgent) {
      // The provider can be constructed without an agent. Initialization still reports its absence.
      return configuredModeWithoutAgent();
    }
  }

  @SuppressForbidden // ConfigHelper and ConfigProvider are not present without the Java agent.
  private static String configuredModeWithoutAgent() {
    final String property = System.getProperty("dd.feature.flags.experimentation.opt.out.mode");
    if (property != null) {
      return property;
    }
    final String environment = System.getenv("DD_FEATURE_FLAGS_EXPERIMENTATION_OPT_OUT_MODE");
    return environment != null ? environment : "disabled";
  }

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
        // Do not expose customer context values in the diagnostic.
      }
    }
    throw new IllegalArgumentException(INVALID_TIMESTAMP);
  }

  /** Checks prepared activation states before selecting an allocation. */
  static boolean hasInvalidActivation(final List<Allocation> allocations) {
    if (!ActivationSupport.INVALID_STATE_AVAILABLE || allocations == null) {
      return false;
    }
    for (final Allocation allocation : allocations) {
      if (allocation != null && allocation.invalidRandomizationActivation) {
        return true;
      }
    }
    return false;
  }

  static boolean excludes(
      final ExperimentationOptOutMode mode, final Instant cutoff, final Allocation allocation) {
    if (cutoff == null) {
      return false;
    }
    if (!Boolean.TRUE.equals(allocation.doLog)) {
      return false;
    }
    return mode == ExperimentationOptOutMode.IMMEDIATE
        || !ActivationSupport.AVAILABLE
        || allocation.randomizationActivatedAt == null
        || !allocation.randomizationActivatedAt.isBefore(cutoff);
  }

  static boolean supportsActivation(final Class<?> allocationType) {
    try {
      return allocationType.getField("randomizationActivatedAt").getType() == Instant.class;
    } catch (ReflectiveOperationException | LinkageError | SecurityException unavailable) {
      return false;
    }
  }

  static boolean supportsInvalidActivation(final Class<?> allocationType) {
    try {
      return allocationType.getField("invalidRandomizationActivation").getType() == boolean.class;
    } catch (ReflectiveOperationException | LinkageError | SecurityException unavailable) {
      return false;
    }
  }

  /** Probe only when future mode is evaluated; older agents have unknown activation. */
  private static final class ActivationSupport {
    private static final boolean AVAILABLE = supportsActivation(Allocation.class);
    private static final boolean INVALID_STATE_AVAILABLE =
        supportsInvalidActivation(Allocation.class);
  }
}
