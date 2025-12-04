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
  public static final String PROFILING_TAGS = "profiling.tags";
  public static final String PROFILING_START_DELAY = "profiling.start-delay";
  public static final int PROFILING_START_DELAY_DEFAULT = 10;
  public static final String PROFILING_START_FORCE_FIRST = "profiling.start-force-first";
  public static final boolean PROFILING_START_FORCE_FIRST_DEFAULT = false;
  public static final String PROFILING_UPLOAD_PERIOD = "profiling.upload.period";
  public static final int PROFILING_UPLOAD_PERIOD_DEFAULT = 60;
  public static final String PROFILING_TEMPLATE_OVERRIDE_FILE =
      "profiling.jfr-template-override-file";
  public static final String PROFILING_UPLOAD_TIMEOUT = "profiling.upload.timeout";
  public static final int PROFILING_UPLOAD_TIMEOUT_DEFAULT = 30;

  /**
   * @deprecated Use {@link #PROFILING_DEBUG_UPLOAD_COMPRESSION} instead. This will be removed in a
   *     future release.
   */
  @Deprecated
  public static final String PROFILING_UPLOAD_COMPRESSION = "profiling.upload.compression";

  public static final String PROFILING_PROXY_HOST = "profiling.proxy.host";
  public static final String PROFILING_PROXY_PORT = "profiling.proxy.port";
  public static final int PROFILING_PROXY_PORT_DEFAULT = 8080;
  public static final String PROFILING_PROXY_USERNAME = "profiling.proxy.username";
  public static final String PROFILING_PROXY_PASSWORD = "profiling.proxy.password";
  public static final String PROFILING_EXCEPTION_SAMPLE_LIMIT = "profiling.exception.sample.limit";
  public static final int PROFILING_EXCEPTION_SAMPLE_LIMIT_DEFAULT = 10_000;
  public static final String PROFILING_EXCEPTION_RECORD_MESSAGE =
      "profiling.exception.record.message";
  public static final boolean PROFILING_EXCEPTION_RECORD_MESSAGE_DEFAULT = true;

  public static final String PROFILING_BACKPRESSURE_SAMPLING_ENABLED =
      "profiling.backpressure.sampling.enabled";
  public static final boolean PROFILING_BACKPRESSURE_SAMPLING_ENABLED_DEFAULT = false;
  public static final String PROFILING_BACKPRESSURE_SAMPLE_LIMIT =
      "profiling.backpressure.sample.limit";
  public static final int PROFILING_BACKPRESSURE_SAMPLE_LIMIT_DEFAULT = 10_000;

  public static final String PROFILING_DIRECT_ALLOCATION_SAMPLE_LIMIT =
      "profiling.direct.allocation.sample.limit";
  public static final int PROFILING_DIRECT_ALLOCATION_SAMPLE_LIMIT_DEFAULT = 2_000;
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

  public static final String PROFILING_JFR_REPOSITORY_BASE = "profiling.jfr.repository.base";
  public static final String PROFILING_JFR_REPOSITORY_BASE_DEFAULT =
      System.getProperty("java.io.tmpdir") + "/dd/jfr";

  public static final String PROFILING_DATADOG_PROFILER_ENABLED = "profiling.ddprof.enabled";

  public static final String PROFILING_DIRECT_ALLOCATION_ENABLED =
      "profiling.directallocation.enabled";
  public static final boolean PROFILING_DIRECT_ALLOCATION_ENABLED_DEFAULT = false;

  public static final String PROFILING_STACKDEPTH = "profiling.stackdepth";
  public static final int PROFILING_STACKDEPTH_DEFAULT = 512;

  // Java profiler lib needs to be extracted from JAR and placed into the scratch location
  // By default the scratch is the os temp directory but can be overridden by user
  public static final String PROFILING_DATADOG_PROFILER_SCRATCH = "profiling.ddprof.scratch";

  public static final String PROFILING_DATADOG_PROFILER_LIBPATH = "profiling.ddprof.debug.lib";
  public static final String PROFILING_DATADOG_PROFILER_ALLOC_ENABLED =
      "profiling.ddprof.alloc.enabled";
  public static final String PROFILING_DATADOG_PROFILER_ALLOC_INTERVAL =
      "profiling.ddprof.alloc.interval";
  public static final int PROFILING_DATADOG_PROFILER_ALLOC_INTERVAL_DEFAULT = 256 * 1024;
  public static final String PROFILING_DATADOG_PROFILER_CPU_ENABLED =
      "profiling.ddprof.cpu.enabled";
  public static final boolean PROFILING_DATADOG_PROFILER_CPU_ENABLED_DEFAULT = true;
  public static final String PROFILING_DATADOG_PROFILER_CPU_INTERVAL =
      "profiling.ddprof.cpu.interval.ms";
  public static final int PROFILING_DATADOG_PROFILER_CPU_INTERVAL_DEFAULT = 10;
  public static final String PROFILING_DATADOG_PROFILER_WALL_ENABLED =
      "profiling.ddprof.wall.enabled";
  public static final boolean PROFILING_DATADOG_PROFILER_WALL_ENABLED_DEFAULT = true;
  public static final String PROFILING_DATADOG_PROFILER_WALL_INTERVAL =
      "profiling.ddprof.wall.interval.ms";
  public static final int PROFILING_DATADOG_PROFILER_WALL_INTERVAL_DEFAULT = 50;

  public static final int PROFILING_DATADOG_PROFILER_J9_CPU_INTERVAL_DEFAULT = 50;

  public static final String PROFILING_DATADOG_PROFILER_WALL_COLLAPSING =
      "profiling.ddprof.wall.collapsing";
  public static final boolean PROFILING_DATADOG_PROFILER_WALL_COLLAPSING_DEFAULT = true;

  public static final String PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER =
      "profiling.ddprof.wall.context.filter";
  public static final boolean PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER_DEFAULT = true;

  public static final String PROFILING_DATADOG_PROFILER_WALL_JVMTI =
      "profiling.experimental.ddprof.wall.jvmti";
  public static final boolean PROFILING_DATADOG_PROFILER_WALL_JVMTI_DEFAULT = false;

  public static final String PROFILING_DATADOG_PROFILER_SCHEDULING_EVENT =
      "profiling.experimental.ddprof.scheduling.event";

  public static final String PROFILING_DATADOG_PROFILER_SCHEDULING_EVENT_INTERVAL =
      "profiling.experimental.ddprof.scheduling.event.interval";

  public static final String PROFILING_PROCESS_CONTEXT_ENABLED =
      "profiling.experimental.process_context.enabled";
  public static final boolean PROFILING_PROCESS_CONTEXT_ENABLED_DEFAULT = false;

  public static final String PROFILING_DATADOG_PROFILER_LOG_LEVEL = "profiling.ddprof.loglevel";

  public static final String PROFILING_DATADOG_PROFILER_LOG_LEVEL_DEFAULT = "NONE";
  public static final String PROFILING_DATADOG_PROFILER_STACKDEPTH = "profiling.ddprof.stackdepth";
  public static final String PROFILING_DATADOG_PROFILER_CSTACK = "profiling.ddprof.cstack";
  public static final String PROFILING_DATADOG_PROFILER_CSTACK_DEFAULT = "vm";
  public static final String PROFILING_DATADOG_PROFILER_SAFEMODE = "profiling.ddprof.safemode";

  private static final int POP_METHOD = 4;
  private static final int LAST_JAVA_PC = 16;
  public static final int PROFILING_DATADOG_PROFILER_SAFEMODE_DEFAULT = POP_METHOD | LAST_JAVA_PC;

  public static final String PROFILING_DATADOG_PROFILER_LINE_NUMBERS =
      "profiling.ddprof.linenumbers";

  public static final boolean PROFILING_DATADOG_PROFILER_LINE_NUMBERS_DEFAULT = true;

  @Deprecated
  public static final String PROFILING_DATADOG_PROFILER_MEMLEAK_ENABLED =
      "profiling.ddprof.memleak.enabled";

  @Deprecated
  public static final String PROFILING_DATADOG_PROFILER_MEMLEAK_INTERVAL =
      "profiling.ddprof.memleak.interval";

  @Deprecated
  public static final String PROFILING_DATADOG_PROFILER_MEMLEAK_CAPACITY =
      "profiling.ddprof.memleak.capacity";

  public static final String PROFILING_DATADOG_PROFILER_LIVEHEAP_ENABLED =
      "profiling.ddprof.liveheap.enabled";
  public static final boolean PROFILING_DATADOG_PROFILER_LIVEHEAP_ENABLED_DEFAULT = false;
  public static final String PROFILING_DATADOG_PROFILER_LIVEHEAP_INTERVAL =
      "profiling.ddprof.liveheap.interval";
  public static final String PROFILING_DATADOG_PROFILER_LIVEHEAP_CAPACITY =
      "profiling.ddprof.liveheap.capacity";
  public static final int PROFILING_DATADOG_PROFILER_LIVEHEAP_CAPACITY_DEFAULT = 1024;
  public static final String PROFILING_DATADOG_PROFILER_LIVEHEAP_TRACK_HEAPSIZE =
      "profiling.ddprof.liveheap.track_size.enabled";
  public static final boolean PROFILING_DATADOG_PROFILER_LIVEHEAP_TRACK_HEAPSIZE_DEFAFULT = true;
  public static final String PROFILING_DATADOG_PROFILER_LIVEHEAP_SAMPLE_PERCENT =
      "profiling.ddprof.liveheap.sample_percent";
  public static final int PROFILING_DATADOG_PROFILER_LIVEHEAP_SAMPLE_PERCENT_DEFAULT =
      50; // default to 10% of allocation samples

  public static final String PROFILING_ENDPOINT_COLLECTION_ENABLED =
      "profiling.endpoint.collection.enabled";
  public static final boolean PROFILING_ENDPOINT_COLLECTION_ENABLED_DEFAULT = true;

  public static final String PROFILING_JFR_REPOSITORY_MAXSIZE = "profiling.jfr.repository.maxsize";
  public static final int PROFILING_JFR_REPOSITORY_MAXSIZE_DEFAULT =
      64 * 1024 * 1024; // 64MB default

  public static final String PROFILING_UPLOAD_SUMMARY_ON_413 = "profiling.upload.summary-on-413";
  public static final boolean PROFILING_UPLOAD_SUMMARY_ON_413_DEFAULT = false;

  public static final String PROFILING_TEMP_DIR = "profiling.tempdir";
  public static final String PROFILING_TEMP_DIR_DEFAULT = System.getProperty("java.io.tmpdir");

  // Not intended for production use
  public static final String PROFILING_AGENTLESS = "profiling.agentless";
  public static final boolean PROFILING_AGENTLESS_DEFAULT = false;

  public static final String PROFILING_DISABLED_EVENTS = "profiling.disabled.events";
  public static final String PROFILING_ENABLED_EVENTS = "profiling.enabled.events";

  public static final String PROFILING_DEBUG_DUMP_PATH = "profiling.debug.dump_path";
  public static final String PROFILING_DEBUG_JFR_DISABLED = "profiling.debug.jfr.disabled";

  // spotless:off
  /**
   * Configuration for profile upload compression.<br><br> Supported values are:
   * <ul>
   * <li><b>on</b>: equivalent to <b>zstd</b></li>
   * <li><b>off</b>: disables compression</li>
   * <li><b>lz4</b>: uses LZ4 compression (fast with moderate compression ratio)</li>
   * <li><b>gzip</b>: uses GZIP compression (higher compression ratio but slower)</li>
   * <li><b>zstd</b>: uses ZSTD compression (high compression ratio with reasonable performance)</li>
   * </ul>
   */
  // spotless:on
  public static final String PROFILING_DEBUG_UPLOAD_COMPRESSION =
      "profiling.debug.upload.compression";

  public static final String PROFILING_DEBUG_UPLOAD_COMPRESSION_DEFAULT = "zstd";

  public static final String PROFILING_CONTEXT_ATTRIBUTES = "profiling.context.attributes";

  public static final String PROFILING_CONTEXT_ATTRIBUTES_SPAN_NAME_ENABLED =
      "profiling.context.attributes.span.name.enabled";

  public static final String PROFILING_CONTEXT_ATTRIBUTES_RESOURCE_NAME_ENABLED =
      "profiling.context.attributes.resource.name.enabled";

  public static final String PROFILING_QUEUEING_TIME_ENABLED = "profiling.queueing.time.enabled";

  public static final boolean PROFILING_QUEUEING_TIME_ENABLED_DEFAULT = true;

  public static final String PROFILING_SMAP_COLLECTION_ENABLED =
      "profiling.smap.collection.enabled";

  public static final boolean PROFILING_SMAP_COLLECTION_ENABLED_DEFAULT = false;

  public static final String PROFILING_SMAP_AGGREGATION_ENABLED =
      "profiling.smap.aggregation.enabled";

  public static final boolean PROFILING_SMAP_AGGREGATION_ENABLED_DEFAULT = false;

  public static final String PROFILING_QUEUEING_TIME_THRESHOLD_MILLIS =
      "profiling.queueing.time.threshold.millis";

  public static final long PROFILING_QUEUEING_TIME_THRESHOLD_MILLIS_DEFAULT = 50;

  public static final String PROFILING_ULTRA_MINIMAL = "profiling.ultra.minimal";

  public static final String PROFILING_HEAP_HISTOGRAM_ENABLED = "profiling.heap.histogram.enabled";
  public static final boolean PROFILING_HEAP_HISTOGRAM_ENABLED_DEFAULT = false;

  public static final String PROFILING_HEAP_HISTOGRAM_MODE = "profiling.heap.histogram.mode";
  public static final String PROFILING_HEAP_HISTOGRAM_MODE_DEFAULT = "aftergc";

  public static final String PROFILING_HEAP_TRACK_GENERATIONS = "profiling.heap.track.generations";
  public static final boolean PROFILING_HEAP_TRACK_GENERATIONS_DEFAULT = false;

  public static final String PROFILING_TIMELINE_EVENTS_ENABLED =
      "profiling.timeline.events.enabled";
  public static final boolean PROFILING_TIMELINE_EVENTS_ENABLED_DEFAULT = true;

  public static final String PROFILING_DETAILED_DEBUG_LOGGING = "profiling.detailed.debug.logging";
  public static final boolean PROFILING_DETAILED_DEBUG_LOGGING_DEFAULT = false;

  // OTLP Profiles Format Support
  public static final String PROFILING_OTLP_ENABLED = "profiling.otlp.enabled";
  public static final boolean PROFILING_OTLP_ENABLED_DEFAULT = false;

  public static final String PROFILING_OTLP_INCLUDE_ORIGINAL_PAYLOAD =
      "profiling.otlp.include.original.payload";
  public static final boolean PROFILING_OTLP_INCLUDE_ORIGINAL_PAYLOAD_DEFAULT = false;

  public static final String PROFILING_OTLP_URL = "profiling.otlp.url";
  public static final String PROFILING_OTLP_URL_DEFAULT = ""; // Empty = derive from agent URL

  public static final String PROFILING_OTLP_COMPRESSION_ENABLED =
      "profiling.otlp.compression.enabled";
  public static final boolean PROFILING_OTLP_COMPRESSION_ENABLED_DEFAULT = true;

  private ProfilingConfig() {}
}
