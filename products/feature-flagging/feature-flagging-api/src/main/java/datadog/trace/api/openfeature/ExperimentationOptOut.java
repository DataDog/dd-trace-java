package datadog.trace.api.openfeature;

import datadog.trace.api.featureflag.config.FeatureFlaggingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.Value;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
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
      default:
        throw new IllegalArgumentException(
            "DD_FEATURE_FLAGS_EXPERIMENTATION_OPT_OUT_MODE must be disabled or immediate");
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

  static boolean exclude(final ExperimentationOptOutMode mode, final EvaluationContext context) {
    if (mode != ExperimentationOptOutMode.IMMEDIATE || !context.keySet().contains(ATTRIBUTE)) {
      return false;
    }
    final Value value = context.getValue(ATTRIBUTE);
    if (value != null && value.isString() && validTimestamp(value.asString())) {
      return true;
    }
    throw new IllegalArgumentException(INVALID_TIMESTAMP);
  }

  private static boolean validTimestamp(final String value) {
    try {
      // Use the allocation startAt/endAt parser's timestamp contract.
      DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(value, Instant::from);
      return true;
    } catch (final DateTimeException e) {
      return false;
    }
  }
}
