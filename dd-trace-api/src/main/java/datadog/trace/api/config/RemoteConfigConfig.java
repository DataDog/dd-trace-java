package datadog.trace.api.config;

public class RemoteConfigConfig {
  public static final String REMOTE_CONFIG_ENABLED = "remote_config.enabled";
  public static final String REMOTE_CONFIG_URL = "remote_config.url";
  public static final String REMOTE_CONFIG_INITIAL_POLL_INTERVAL =
      "remote_config.initial.poll.interval"; // s
  public static final String REMOTE_CONFIG_MAX_PAYLOAD_SIZE =
      "remote_config.max.payload.size"; // kb

  private RemoteConfigConfig() {}
}
