package datadog.trace.api.config;

public class FeatureFlaggingConfig {

  public static final String FLAGGING_ENABLED = "flagging.enabled";

  /** Replaced by {@link #FLAGGING_ENABLED}. */
  @Deprecated
  public static final String FLAGGING_PROVIDER_ENABLED = "experimental.flagging.provider.enabled";

  public static final String FLAGGING_CONFIGURATION_SOURCE = "flagging.configuration.source";
  public static final String FLAGGING_CONFIGURATION_SOURCE_BASE_URL =
      "flagging.configuration.source.base.url";
  public static final String FLAGGING_CONFIGURATION_SOURCE_POLL_INTERVAL_SECONDS =
      "flagging.configuration.source.poll.interval.seconds";
  public static final String FLAGGING_CONFIGURATION_SOURCE_REQUEST_TIMEOUT_SECONDS =
      "flagging.configuration.source.request.timeout.seconds";
  public static final String FLAGGING_CONFIGURATION_SOURCE_EXTRA_HEADERS =
      "flagging.configuration.source.extra.headers";

  private FeatureFlaggingConfig() {}
}
