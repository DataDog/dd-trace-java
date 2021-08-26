package datadog.trace.api.config;

/**
 * These config options will only work with dd-java-agent, not with dd-trace-ot.
 *
 * <p>Configure via system properties, environment variables, or config properties file. See online
 * documentation for details.
 */
public final class ProfilingConfig {
  public static final String PROFILING_ENABLED = "profiling.enabled";
  public static final String PROFILING_ALLOCATION_ENABLED = "profiling.allocation.enabled";
  public static final String PROFILING_HEAP_ENABLED = "profiling.heap.enabled";
  @Deprecated // Use dd.site instead
  public static final String PROFILING_URL = "profiling.url";
  @Deprecated // Use dd.api-key instead
  public static final String PROFILING_API_KEY_OLD = "profiling.api-key";
  @Deprecated // Use dd.api-key-file instead
  public static final String PROFILING_API_KEY_FILE_OLD = "profiling.api-key-file";
  @Deprecated // Use dd.api-key instead
  public static final String PROFILING_API_KEY_VERY_OLD = "profiling.apikey";
  @Deprecated // Use dd.api-key-file instead
  public static final String PROFILING_API_KEY_FILE_VERY_OLD = "profiling.apikey.file";
  public static final String PROFILING_TEMPLATE = "profiling.template";
  public static final String PROFILING_TEMPLATE_DEFAULT = "default";
  public static final String PROFILING_TAGS = "profiling.tags";
  public static final String PROFILING_START_DELAY = "profiling.start-delay";
  // DANGEROUS! May lead on sigsegv on JVMs before 14
  // Not intended for production use
  public static final String PROFILING_START_FORCE_FIRST =
      "profiling.experimental.start-force-first";
  public static final String PROFILING_UPLOAD_PERIOD = "profiling.upload.period";
  public static final String PROFILING_TEMPLATE_OVERRIDE_FILE =
      "profiling.jfr-template-override-file";
  public static final String PROFILING_UPLOAD_TIMEOUT = "profiling.upload.timeout";
  public static final String PROFILING_UPLOAD_COMPRESSION = "profiling.upload.compression";
  public static final String PROFILING_PROXY_HOST = "profiling.proxy.host";
  public static final String PROFILING_PROXY_PORT = "profiling.proxy.port";
  public static final String PROFILING_PROXY_USERNAME = "profiling.proxy.username";
  public static final String PROFILING_PROXY_PASSWORD = "profiling.proxy.password";
  public static final String PROFILING_EXCEPTION_SAMPLE_LIMIT = "profiling.exception.sample.limit";
  public static final String PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS =
      "profiling.exception.histogram.top-items";
  public static final String PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE =
      "profiling.exception.histogram.max-collection-size";
  public static final String PROFILING_EXCLUDE_AGENT_THREADS = "profiling.exclude.agent-threads";
  public static final String PROFILING_HOTSPOTS_ENABLED = "profiling.hotspots.enabled";

  public static final String PROFILING_LEGACY_TRACING_INTEGRATION =
      "profiling.legacy.tracing.integration";
  public static final String PROFILING_CHECKPOINTS_RECORD_CPU_TIME =
      "profiling.checkpoints.record.cpu.time";
  public static final String PROFILING_CHECKPOINTS_RATE_LIMIT = "profiling.checkpoints.rate.limit";
  public static final int PROFILING_CHECKPOINTS_RATE_LIMIT_DEFAULT = 50000;

  // Not intended for production use
  public static final String PROFILING_AGENTLESS = "profiling.agentless";

  private ProfilingConfig() {}
}
