package datadog.trace.api.config;

/**
 * These config options will only work with dd-java-agent, not with dd-trace-ot.
 *
 * <p>Configure via system properties, environment variables, or config properties file. See online
 * documentation for details.
 */
public final class ProfilingConfig {
  public static final String PROFILING_ENABLED = "profiling.enabled";
  public static final boolean PROFILING_ENABLED_DEFAULT = false;
  public static final String PROFILING_ALLOCATION_ENABLED = "profiling.allocation.enabled";
  public static final String PROFILING_HEAP_ENABLED = "profiling.heap.enabled";
  public static final boolean PROFILING_HEAP_ENABLED_DEFAULT = false;
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
  public static final int PROFILING_START_DELAY_DEFAULT = 10;
  // DANGEROUS! May lead on sigsegv on JVMs before 14
  // Not intended for production use
  public static final String PROFILING_START_FORCE_FIRST =
      "profiling.experimental.start-force-first";
  public static final boolean PROFILING_START_FORCE_FIRST_DEFAULT = false;
  public static final String PROFILING_UPLOAD_PERIOD = "profiling.upload.period";
  public static final int PROFILING_UPLOAD_PERIOD_DEFAULT = 60;
  public static final String PROFILING_TEMPLATE_OVERRIDE_FILE =
      "profiling.jfr-template-override-file";
  public static final String PROFILING_UPLOAD_TIMEOUT = "profiling.upload.timeout";
  public static final int PROFILING_UPLOAD_TIMEOUT_DEFAULT = 30;
  public static final String PROFILING_UPLOAD_COMPRESSION = "profiling.upload.compression";
  public static final String PROFILING_UPLOAD_COMPRESSION_DEFAULT = "on";
  public static final String PROFILING_PROXY_HOST = "profiling.proxy.host";
  public static final String PROFILING_PROXY_PORT = "profiling.proxy.port";
  public static final int PROFILING_PROXY_PORT_DEFAULT = 8080;
  public static final String PROFILING_PROXY_USERNAME = "profiling.proxy.username";
  public static final String PROFILING_PROXY_PASSWORD = "profiling.proxy.password";
  public static final String PROFILING_EXCEPTION_SAMPLE_LIMIT = "profiling.exception.sample.limit";
  public static final int PROFILING_EXCEPTION_SAMPLE_LIMIT_DEFAULT = 10_000;
  public static final String PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS =
      "profiling.exception.histogram.top-items";
  public static final int PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS_DEFAULT = 50;
  public static final String PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE =
      "profiling.exception.histogram.max-collection-size";
  public static final int PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE_DEFAULT = 10_000;
  public static final String PROFILING_EXCLUDE_AGENT_THREADS = "profiling.exclude.agent-threads";
  public static final String PROFILING_HOTSPOTS_ENABLED = "profiling.hotspots.enabled";

  public static final String PROFILING_AUXILIARY_TYPE = "profiling.auxiliary";
  public static final String PROFILING_AUXILIARY_TYPE_DEFAULT = "none";

  public static final String PROFILING_ASYNC_ENABLED = "profiling.async.enabled";
  public static final boolean PROFILING_ASYNC_ENABLED_DEFAULT = false;
  public static final String PROFILING_ASYNC_LIBPATH = "profiling.async.lib";
  public static final String PROFILING_ASYNC_ALLOC_ENABLED = "profiling.async.alloc.enabled";
  public static final boolean PROFILING_ASYNC_ALLOC_ENABLED_DEFAULT = false;
  public static final String PROFILING_ASYNC_ALLOC_INTERVAL = "profiling.async.alloc.interval";
  public static final int PROFILING_ASYNC_ALLOC_INTERVAL_DEFAULT = 256 * 1024;
  public static final String PROFILING_ASYNC_CPU_ENABLED = "profiling.async.cpu.enabled";
  public static final boolean PROFILING_ASYNC_CPU_ENABLED_DEFAULT = true;
  public static final String PROFILING_ASYNC_CPU_MODE = "profiling.async.cpu.mode";
  public static final String PROFILING_ASYNC_CPU_MODE_DEFAULT = "cpu";
  public static final String PROFILING_ASYNC_CPU_INTERVAL = "profiling.async.cpu.interval.ms";
  public static final int PROFILING_ASYNC_CPU_INTERVAL_DEFAULT = 10;
  public static final String PROFILING_ASYNC_CPU_STACKDEPTH = "profiling.async.cpu.stackdepth";
  public static final int PROFILING_ASYNC_CPU_STACKDEPTH_DEFAULT = 512;
  public static final String PROFILING_ASYNC_CPU_SAFEMODE = "profiling.async.cpu.safemode";
  public static final int PROFILING_ASYNC_CPU_SAFEMODE_DEFAULT = 12; // POP_FRAME|SCAN_STACK
  public static final String PROFILING_ASYNC_MEMLEAK_ENABLED = "profiling.async.memleak.enabled";
  public static final boolean PROFILING_ASYNC_MEMLEAK_ENABLED_DEFAULT = false;
  public static final String PROFILING_ASYNC_MEMLEAK_INTERVAL = "profiling.async.memleak.interval";
  public static final String PROFILING_ASYNC_MEMLEAK_CAPACITY = "profiling.async.memleak.capacity";
  public static final int PROFILING_ASYNC_MEMLEAK_CAPACITY_DEFAULT = 1024;

  public static final String PROFILING_TRACING_CONTEXT_ENABLED =
      "profiling.tracing_context.enabled";
  public static final boolean PROFILING_TRACING_CONTEXT_ENABLED_DEFAULT = false;
  public static final String PROFILING_TRACING_CONTEXT_TRACKER_INACTIVE_SEC =
      "profiling.tracing_context.tracker.inactive.seconds";
  public static final int PROFILING_TRACING_CONTEXT_TRACKER_INACTIVE_DEFAULT = 90;
  public static final String PROFILING_TRACING_CONTEXT_RESERVED_MEMORY_SIZE =
      "profiling.tracing_context.memory.bytes";
  public static final int PROFILING_TRACING_CONTEXT_RESERVED_MEMORY_SIZE_DEFAULT =
      32 * 1024 * 1024; // 32MB

  public static final String PROFILING_TRACING_CONTEXT_RESERVED_MEMORY_TYPE =
      "profiling.tracing_context.memory.type";
  public static final String PROFILING_TRACING_CONTEXT_RESERVED_MEMORY_TYPE_DEFAULT = "heap";

  public static final String PROFILING_TRACING_CONTEXT_MAX_SIZE =
      "profiling.tracing_context.max_size.bytes";
  public static final int PROFILING_TRACING_CONTEXT_MAX_SIZE_DEFAULT =
      20_000; // 20k bytes is the default

  public static final String PROFILING_LEGACY_TRACING_INTEGRATION =
      "profiling.legacy.tracing.integration";
  public static final boolean PROFILING_LEGACY_TRACING_INTEGRATION_DEFAULT = true;
  public static final String PROFILING_CHECKPOINTS_RECORD_CPU_TIME =
      "profiling.checkpoints.record.cpu.time";
  public static final String PROFILING_CHECKPOINTS_SAMPLER_RATE_LIMIT =
      "profiling.checkpoints.sampler.rate-limit";
  public static final int PROFILING_CHECKPOINTS_SAMPLER_RATE_LIMIT_DEFAULT = 100000;
  public static final String PROFILING_CHECKPOINTS_SAMPLER_WINDOW_MS =
      "profiling.checkpoints.sampler.sliding-window.ms";
  public static final int PROFILING_CHECKPOINTS_SAMPLER_WINDOW_MS_DEFAULT = 5000;
  public static final String PROFILING_CHECKPOINTS_SAMPLER_LIMIT =
      "profiling.checkpoints.sampler.limit";
  public static final int PROFILING_CHECKPOINTS_SAMPLER_LIMIT_DEFAULT = 500_000;
  public static final String PROFILING_ENDPOINT_COLLECTION_ENABLED =
      "profiling.endpoint.collection.enabled";
  public static final boolean PROFILING_ENDPOINT_COLLECTION_ENABLED_DEFAULT = true;

  public static final String PROFILING_JFR_REPOSITORY_MAXSIZE = "profiling.jfr.repository.maxsize";
  public static final int PROFILING_JFR_REPOSITORY_MAXSIZE_DEFAULT =
      64 * 1024 * 1024; // 64MB default

  public static final String PROFILING_UPLOAD_SUMMARY_ON_413 = "profiling.upload.summary-on-413";
  public static final boolean PROFILING_UPLOAD_SUMMARY_ON_413_DEFAULT = false;

  // Not intended for production use
  public static final String PROFILING_AGENTLESS = "profiling.agentless";
  public static final boolean PROFILING_AGENTLESS_DEFAULT = false;

  private ProfilingConfig() {}
}
