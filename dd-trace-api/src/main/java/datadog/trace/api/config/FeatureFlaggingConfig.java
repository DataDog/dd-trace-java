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

  /**
   * The number of slots of the table sharing the flag metadata of evaluations that resolve the same
   * split in the same millisecond, rounded up to a power of two. {@code 0} builds the metadata of
   * each evaluation. Default: {@code 1024}. Maps to {@code
   * DD_FEATURE_FLAGS_EVALUATION_METADATA_TABLE_SIZE}.
   */
  public static final String FEATURE_FLAGS_EVALUATION_METADATA_TABLE_SIZE =
      "feature.flags.evaluation.metadata.table.size";

  /**
   * The width, in milliseconds, of the time bucket in which evaluations of a split share their flag
   * metadata, including its evaluation timestamp, so evaluations report up to the width minus one
   * millisecond early. At most {@code 10000}. {@code 0} leaves the timestamp out of the metadata,
   * so the hooks read their own clock, and {@code 1} keeps the timestamps exact. Default: {@code
   * 10}. Maps to {@code DD_FEATURE_FLAGS_EVALUATION_METADATA_TIME_BUCKET_MS}.
   */
  public static final String FEATURE_FLAGS_EVALUATION_METADATA_TIME_BUCKET_MS =
      "feature.flags.evaluation.metadata.time.bucket.ms";

  private FeatureFlaggingConfig() {}
}
