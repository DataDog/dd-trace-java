package datadog.trace.api.config;

/**
 * These config options will only work with dd-java-agent, not with dd-trace-ot.
 *
 * <p>Configure via system properties, environment variables, or config properties file. See online
 * documentation for details.
 */
public final class CrashReportingConfig {

  public static final String CRASH_REPORTING_URL = "crashreporting.url";

  public static final String CRASH_REPORTING_TAGS = "crashreporting.tags";

  public final String CRASH_REPORTING_UPLOAD_TIMEOUT = "crashreporting.upload.timeout";
  public final int CRASH_REPORTING_UPLOAD_TIMEOUT_DEFAULT = 30;

  public final String CRASH_REPORTING_PROXY_HOST = "crashreporting.proxy.host";
  public final String CRASH_REPORTING_PROXY_PORT = "crashreporting.proxy.port";
  public final String CRASH_REPORTING_PROXY_USERNAME = "crashreporting.proxy.username";
  public final String CRASH_REPORTING_PROXY_PASSWORD = "crashreporting.proxy.password";

  // Not intended for production use
  public static final String CRASH_REPORTING_AGENTLESS = "crashreporting.agentless";
  public static final boolean CRASH_REPORTING_AGENTLESS_DEFAULT = false;

  private CrashReportingConfig() {}
}
