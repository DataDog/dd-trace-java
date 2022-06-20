package datadog.trace.api.config;

/**
 * These config options will only work with dd-java-agent, not with dd-trace-ot.
 *
 * <p>Configure via system properties, environment variables, or config properties file. See online
 * documentation for details.
 */
public final class CrashTrackingConfig {

  public static final String CRASH_TRACKING_URL = "crashtracking.url";

  public static final String CRASH_TRACKING_TAGS = "crashtracking.tags";

  public final String CRASH_TRACKING_UPLOAD_TIMEOUT = "crashtracking.upload.timeout";
  public final int CRASH_TRACKING_UPLOAD_TIMEOUT_DEFAULT = 30;

  public final String CRASH_TRACKING_PROXY_HOST = "crashtracking.proxy.host";
  public final String CRASH_TRACKING_PROXY_PORT = "crashtracking.proxy.port";
  public final String CRASH_TRACKING_PROXY_USERNAME = "crashtracking.proxy.username";
  public final String CRASH_TRACKING_PROXY_PASSWORD = "crashtracking.proxy.password";

  // Not intended for production use
  public static final String CRASH_TRACKING_AGENTLESS = "crashtracking.agentless";
  public static final boolean CRASH_TRACKING_AGENTLESS_DEFAULT = false;

  private CrashTrackingConfig() {}
}
