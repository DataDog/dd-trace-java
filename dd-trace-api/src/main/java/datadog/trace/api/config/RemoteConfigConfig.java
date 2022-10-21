package datadog.trace.api.config;

public class RemoteConfigConfig {
  public static final String REMOTE_CONFIG_ENABLED = "remote_config.enabled";
  public static final String REMOTE_CONFIG_INTEGRITY_CHECK_ENABLED =
      "remote_config.integrity_check.enabled";
  public static final String REMOTE_CONFIG_URL = "remote_config.url";
  public static final String REMOTE_CONFIG_INITIAL_POLL_INTERVAL =
      "remote_config.initial.poll.interval"; // s
  public static final String REMOTE_CONFIG_MAX_PAYLOAD_SIZE =
      "remote_config.max.payload.size"; // kb
  // these two are specified in RCTE1
  public static final String REMOTE_CONFIG_TARGETS_KEY_ID = "rc.targets.key.id";
  public static final String REMOTE_CONFIG_TARGETS_KEY = "rc.targets.key";

  static final boolean DEFAULT_REMOTE_CONFIG_ENABLED = true;
  static final boolean DEFAULT_REMOTE_CONFIG_INTEGRITY_CHECK_ENABLED = false;
  static final int DEFAULT_REMOTE_CONFIG_MAX_PAYLOAD_SIZE = 1024; // KiB
  static final int DEFAULT_REMOTE_CONFIG_INITIAL_POLL_INTERVAL = 5; // s
  static final String DEFAULT_REMOTE_CONFIG_TARGETS_KEY_ID =
      "5c4ece41241a1bb513f6e3e5df74ab7d5183dfffbd71bfd43127920d880569fd";
  static final String DEFAULT_REMOTE_CONFIG_TARGETS_KEY =
      "e3f1f98c9da02a93bb547f448b472d727e14b22455235796fe49863856252508";

  private RemoteConfigConfig() {}
}
