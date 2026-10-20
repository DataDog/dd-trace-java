package datadog.trace.api.openfeature;

import datadog.trace.api.featureflag.config.FeatureFlaggingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.Value;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ExperimentationOptOut {
  static final String ATTRIBUTE = "dd_experimentation_opt_out_at";
  private static final Pattern TIMESTAMP =
      Pattern.compile(
          "([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})"
              + "(?:\\.[0-9]+)?(?:Z|[+-]([0-9]{2}):([0-9]{2}))");

  private ExperimentationOptOut() {}

  static ExperimentationOptOutMode resolve(final ExperimentationOptOutMode override) {
    Objects.requireNonNull(override, "experimentationOptOutMode");
    if (override != ExperimentationOptOutMode.DEFAULT) {
      return override;
    }
    final String configured =
        ConfigProvider.getInstance()
            .getString(
                FeatureFlaggingConfig.FEATURE_FLAGS_EXPERIMENTATION_OPT_OUT_MODE, "disabled");
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

  static boolean exclude(final ExperimentationOptOutMode mode, final EvaluationContext context) {
    if (mode != ExperimentationOptOutMode.IMMEDIATE || !context.keySet().contains(ATTRIBUTE)) {
      return false;
    }
    final Value value = context.getValue(ATTRIBUTE);
    if (value != null && value.isString() && validTimestamp(value.asString())) {
      return true;
    }
    throw new IllegalArgumentException(ATTRIBUTE + " must be an RFC3339 timestamp string");
  }

  private static boolean validTimestamp(final String value) {
    final Matcher match = TIMESTAMP.matcher(value);
    if (!match.matches()) {
      return false;
    }
    try {
      LocalDateTime.of(
          Integer.parseInt(match.group(1)),
          Integer.parseInt(match.group(2)),
          Integer.parseInt(match.group(3)),
          Integer.parseInt(match.group(4)),
          Integer.parseInt(match.group(5)),
          Integer.parseInt(match.group(6)));
      return match.group(7) == null
          || (Integer.parseInt(match.group(7)) < 24 && Integer.parseInt(match.group(8)) < 60);
    } catch (final DateTimeException e) {
      return false;
    }
  }
}
