package datadog.trace.api.config;

/** Constant with names of configuration options for Feature Flags. */
public final class FeatureFlaggingConfig {

  public static final String FEATURE_FLAGS_ENABLED = "feature.flags.enabled";

  /** Use {@link #FEATURE_FLAGS_ENABLED} and {@link #FEATURE_FLAGS_CONFIGURATION_SOURCE}. */
  @Deprecated
  public static final String EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED =
      "experimental.flagging.provider.enabled";

  public static final String FEATURE_FLAGS_CONFIGURATION_SOURCE =
      "feature.flags.configuration.source";

  /**
   * Turns off evaluation telemetry: flag evaluation events, evaluation metrics, and span
   * enrichment. A provider option overrides it. Default: enabled. Maps to {@code
   * DD_FEATURE_FLAGS_EVALUATION_TELEMETRY_ENABLED}.
   */
  public static final String FEATURE_FLAGS_EVALUATION_TELEMETRY_ENABLED =
      "feature.flags.evaluation.telemetry.enabled";

  /**
   * Turns off exposures: no exposure is recorded, deduplicated, sent, or reported to exposure
   * hooks. A provider option overrides it. Default: enabled. Maps to {@code
   * DD_FEATURE_FLAGS_EXPOSURES_ENABLED}.
   */
  public static final String FEATURE_FLAGS_EXPOSURES_ENABLED = "feature.flags.exposures.enabled";

  /**
   * Turns off sending exposures to Datadog while customer exposure hooks keep receiving them.
   * Default: enabled. Maps to {@code DD_FEATURE_FLAGS_EXPOSURES_DATADOG_LOGGING_ENABLED}.
   */
  public static final String FEATURE_FLAGS_EXPOSURES_DATADOG_LOGGING_ENABLED =
      "feature.flags.exposures.datadog.logging.enabled";

  /**
   * Lets the {@code dd_evaluation_telemetry_opt_out} evaluation context attribute opt subjects out
   * of evaluation telemetry. Default: disabled. Maps to {@code
   * DD_FEATURE_FLAGS_EVALUATION_TELEMETRY_PER_SUBJECT_OPT_OUT_ENABLED}.
   */
  public static final String FEATURE_FLAGS_EVALUATION_TELEMETRY_PER_SUBJECT_OPT_OUT_ENABLED =
      "feature.flags.evaluation.telemetry.per.subject.opt.out.enabled";

  /**
   * Lets the {@code dd_experimentation_opt_out_at} evaluation context attribute opt subjects out of
   * experimentation. Maps to {@code DD_FEATURE_FLAGS_EXPERIMENTATION_OPT_OUT_MODE}. Accepted
   * values: {@code disabled} (default), {@code immediate}, and {@code future_experiments_only}.
   */
  public static final String FEATURE_FLAGS_EXPERIMENTATION_OPT_OUT_MODE =
      "feature.flags.experimentation.opt.out.mode";

  private FeatureFlaggingConfig() {}
}
