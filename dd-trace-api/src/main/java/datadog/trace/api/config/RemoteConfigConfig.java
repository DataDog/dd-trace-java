package datadog.trace.api.config;

public class RemoteConfigConfig {
  public static final String REMOTE_CONFIG_ENABLED = "remote_config.enabled";
  public static final String REMOTE_CONFIG_INTEGRITY_CHECK_ENABLED =
      "remote_config.integrity_check.enabled";
  public static final String REMOTE_CONFIG_URL = "remote_config.url";
  public static final String REMOTE_CONFIG_POLL_INTERVAL_SECONDS =
      "remote_config.poll_interval.seconds";
  public static final String REMOTE_CONFIG_MAX_PAYLOAD_SIZE =
      "remote_config.max.payload.size"; // kb
  // these two are specified in RCTE1
  public static final String REMOTE_CONFIG_TARGETS_KEY_ID = "rc.targets.key.id";
  public static final String REMOTE_CONFIG_TARGETS_KEY = "rc.targets.key";

  private RemoteConfigConfig() {}
}
