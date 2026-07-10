package datadog.trace.api.config;

public class FeatureFlaggingConfig {

  public static final String FLAGGING_PROVIDER_ENABLED = "experimental.flagging.provider.enabled";

  public static final String FEATURE_FLAGS_CONFIGURATION_SOURCE =
      "feature.flags.configuration.source";
  public static final String FEATURE_FLAGS_CONFIGURATION_SOURCE_AGENTLESS_POLL_INTERVAL_SECONDS =
      "feature.flags.configuration.source.agentless.poll.interval.seconds";
  public static final String FEATURE_FLAGS_CONFIGURATION_SOURCE_AGENTLESS_REQUEST_TIMEOUT_SECONDS =
      "feature.flags.configuration.source.agentless.request.timeout.seconds";

  private FeatureFlaggingConfig() {}
}
