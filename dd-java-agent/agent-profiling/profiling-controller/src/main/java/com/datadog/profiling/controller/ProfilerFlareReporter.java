package com.datadog.profiling.controller;

import datadog.trace.api.Config;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.flare.TracerFlare;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.zip.ZipOutputStream;

public final class ProfilerFlareReporter implements TracerFlare.Reporter {
  private static final ProfilerFlareReporter INSTANCE = new ProfilerFlareReporter();
  private volatile Exception profilerInitializationException;

  public static void register() {
    TracerFlare.addReporter(INSTANCE);
  }

  public static void reportInitializationException(Exception e) {
    INSTANCE.profilerInitializationException = e;
  }

  @Override
  public void addReportToFlare(ZipOutputStream zip) throws IOException {
    TracerFlare.addText(zip, "profiler_config.txt", getProfilerConfig());
    String templateOverrideFile = Config.get().getProfilingTemplateOverrideFile();
    if (templateOverrideFile != null) {
      try {
        Path path = Paths.get(templateOverrideFile);
        if (Files.exists(path)) {
          String fileContents = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
          TracerFlare.addText(zip, "profiling_template_override.jfp", fileContents);
        }
      } catch (IOException e) {
        // no-op, ignore if we can't read the template override file
      }
    }

    StringBuilder envCheck = new StringBuilder();
    String tempDir = ConfigProvider.getInstance().getString(ProfilingConfig.PROFILING_TEMP_DIR);
    EnvironmentChecker.checkEnvironment(
        tempDir != null ? tempDir : System.getProperty("java.io.tmpdir"), envCheck);
    TracerFlare.addText(zip, "profiler_env.txt", envCheck.toString());
  }

  private String getProfilerConfig() {
    StringBuilder sb = new StringBuilder();
    sb.append("Profiler Configuration\n");
    sb.append("======================\n\n");

    ConfigProvider configProvider = ConfigProvider.getInstance();
    Config config = Config.get();

    sb.append("=== Profiler Initialization Status ===\n");
    if (profilerInitializationException == null) {
      sb.append("Profiler initialized successfully.\n");
    } else {
      sb.append("Profiler initialization failed due to: \n");
      sb.append(profilerInitializationException.getMessage());
      sb.append("\n");
    }

    sb.append("=== Core Settings ===\n");
    appendConfig(
        sb,
        "Profiling Enabled",
        config.isProfilingEnabled(),
        ProfilingConfig.PROFILING_ENABLED_DEFAULT);
    appendConfig(
        sb,
        "Agentless",
        config.isProfilingAgentless(),
        ProfilingConfig.PROFILING_AGENTLESS_DEFAULT);
    appendConfig(
        sb,
        "Start Delay",
        config.getProfilingStartDelay(),
        ProfilingConfig.PROFILING_START_DELAY_DEFAULT,
        " seconds");
    appendConfig(
        sb,
        "Start Force First",
        config.isProfilingStartForceFirst(),
        ProfilingConfig.PROFILING_START_FORCE_FIRST_DEFAULT);
    appendConfig(
        sb,
        "Ultra Minimal",
        configProvider.getString(ProfilingConfig.PROFILING_ULTRA_MINIMAL),
        null);
    appendConfig(
        sb,
        "Detailed Debug Logging",
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_DETAILED_DEBUG_LOGGING,
            ProfilingConfig.PROFILING_DETAILED_DEBUG_LOGGING_DEFAULT),
        ProfilingConfig.PROFILING_DETAILED_DEBUG_LOGGING_DEFAULT);

    sb.append("\n=== Upload Settings ===\n");
    appendConfig(
        sb,
        "Upload Period",
        config.getProfilingUploadPeriod(),
        ProfilingConfig.PROFILING_UPLOAD_PERIOD_DEFAULT,
        " seconds");
    appendConfig(
        sb,
        "Upload Timeout",
        config.getProfilingUploadTimeout(),
        ProfilingConfig.PROFILING_UPLOAD_TIMEOUT_DEFAULT,
        " seconds");
    appendConfig(
        sb,
        "Upload Compression",
        config.getProfilingUploadCompression(),
        ProfilingConfig.PROFILING_DEBUG_UPLOAD_COMPRESSION_DEFAULT);
    appendConfig(
        sb,
        "Upload Summary on 413",
        config.isProfilingUploadSummaryOn413Enabled(),
        ProfilingConfig.PROFILING_UPLOAD_SUMMARY_ON_413_DEFAULT);

    sb.append("\n=== Proxy Settings ===\n");
    appendConfig(sb, "Proxy Host", config.getProfilingProxyHost(), null);
    appendConfig(
        sb,
        "Proxy Port",
        config.getProfilingProxyPort(),
        ProfilingConfig.PROFILING_PROXY_PORT_DEFAULT);
    appendConfig(sb, "Proxy Username", config.getProfilingProxyUsername(), null);
    String proxyPassword = config.getProfilingProxyPassword();
    sb.append("Proxy Password: ")
        .append(proxyPassword != null ? "[REDACTED]" : null)
        .append(" (default: null)\n");

    sb.append("\n=== Allocation Profiling ===\n");
    boolean allocDefault = ProfilingSupport.isObjectAllocationSampleAvailable();
    boolean allocEnabled =
        configProvider.getBoolean(ProfilingConfig.PROFILING_ALLOCATION_ENABLED, allocDefault);
    sb.append("Allocation Profiling Enabled: ")
        .append(allocEnabled)
        .append(" (default: dynamic based on JVM)\n");
    appendConfig(
        sb,
        "Direct Allocation Enabled",
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_DIRECT_ALLOCATION_ENABLED,
            ProfilingConfig.PROFILING_DIRECT_ALLOCATION_ENABLED_DEFAULT),
        ProfilingConfig.PROFILING_DIRECT_ALLOCATION_ENABLED_DEFAULT);
    appendConfig(
        sb,
        "Direct Allocation Sample Limit",
        config.getProfilingDirectAllocationSampleLimit(),
        ProfilingConfig.PROFILING_DIRECT_ALLOCATION_SAMPLE_LIMIT_DEFAULT);

    sb.append("\n=== Heap Profiling ===\n");
    appendConfig(
        sb,
        "Heap Profiling Enabled",
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_HEAP_ENABLED, ProfilingConfig.PROFILING_HEAP_ENABLED_DEFAULT),
        ProfilingConfig.PROFILING_HEAP_ENABLED_DEFAULT);
    appendConfig(
        sb,
        "Heap Histogram Enabled",
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_HEAP_HISTOGRAM_ENABLED,
            ProfilingConfig.PROFILING_HEAP_HISTOGRAM_ENABLED_DEFAULT),
        ProfilingConfig.PROFILING_HEAP_HISTOGRAM_ENABLED_DEFAULT);
    appendConfig(
        sb,
        "Heap Histogram Mode",
        configProvider.getString(
            ProfilingConfig.PROFILING_HEAP_HISTOGRAM_MODE,
            ProfilingConfig.PROFILING_HEAP_HISTOGRAM_MODE_DEFAULT),
        ProfilingConfig.PROFILING_HEAP_HISTOGRAM_MODE_DEFAULT);
    appendConfig(
        sb,
        "Heap Track Generations",
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_HEAP_TRACK_GENERATIONS,
            ProfilingConfig.PROFILING_HEAP_TRACK_GENERATIONS_DEFAULT),
        ProfilingConfig.PROFILING_HEAP_TRACK_GENERATIONS_DEFAULT);

    sb.append("\n=== Exception Profiling ===\n");
    appendConfig(
        sb,
        "Exception Sample Limit",
        config.getProfilingExceptionSampleLimit(),
        ProfilingConfig.PROFILING_EXCEPTION_SAMPLE_LIMIT_DEFAULT);
    appendConfig(
        sb,
        "Exception Record Message",
        config.isProfilingRecordExceptionMessage(),
        ProfilingConfig.PROFILING_EXCEPTION_RECORD_MESSAGE_DEFAULT);
    appendConfig(
        sb,
        "Exception Histogram Top Items",
        config.getProfilingExceptionHistogramTopItems(),
        ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS_DEFAULT);
    appendConfig(
        sb,
        "Exception Histogram Max Collection Size",
        config.getProfilingExceptionHistogramMaxCollectionSize(),
        ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE_DEFAULT);

    sb.append("\n=== Backpressure Profiling ===\n");
    appendConfig(
        sb,
        "Backpressure Sampling Enabled",
        config.isProfilingBackPressureSamplingEnabled(),
        ProfilingConfig.PROFILING_BACKPRESSURE_SAMPLING_ENABLED_DEFAULT);
    appendConfig(
        sb,
        "Backpressure Sample Limit",
        config.getProfilingBackPressureSampleLimit(),
        ProfilingConfig.PROFILING_BACKPRESSURE_SAMPLE_LIMIT_DEFAULT);

    sb.append("\n=== JFR Settings ===\n");
    appendConfig(sb, "JFR Template Override File", config.getProfilingTemplateOverrideFile(), null);
    appendConfig(
        sb,
        "JFR Repository Base",
        configProvider.getString(
            ProfilingConfig.PROFILING_JFR_REPOSITORY_BASE,
            ProfilingConfig.PROFILING_JFR_REPOSITORY_BASE_DEFAULT),
        ProfilingConfig.PROFILING_JFR_REPOSITORY_BASE_DEFAULT);
    appendConfig(
        sb,
        "JFR Repository Max Size",
        configProvider.getInteger(
            ProfilingConfig.PROFILING_JFR_REPOSITORY_MAXSIZE,
            ProfilingConfig.PROFILING_JFR_REPOSITORY_MAXSIZE_DEFAULT),
        ProfilingConfig.PROFILING_JFR_REPOSITORY_MAXSIZE_DEFAULT,
        " bytes");
    appendConfig(
        sb,
        "Debug JFR Disabled",
        configProvider.getString(ProfilingConfig.PROFILING_DEBUG_JFR_DISABLED),
        null);
    appendConfig(
        sb,
        "Disabled Events",
        configProvider.getString(ProfilingConfig.PROFILING_DISABLED_EVENTS),
        null);
    appendConfig(
        sb,
        "Enabled Events",
        configProvider.getString(ProfilingConfig.PROFILING_ENABLED_EVENTS),
        null);

    sb.append("\n=== DDProf Settings ===\n");
    appendConfig(sb, "DDProf Enabled", config.isDatadogProfilerEnabled(), false);
    appendConfig(
        sb,
        "DDProf Scratch",
        configProvider.getString(ProfilingConfig.PROFILING_DATADOG_PROFILER_SCRATCH),
        null);
    appendConfig(
        sb,
        "DDProf Lib Path",
        configProvider.getString(ProfilingConfig.PROFILING_DATADOG_PROFILER_LIBPATH),
        null);
    appendConfig(
        sb,
        "DDProf Log Level",
        configProvider.getString(
            ProfilingConfig.PROFILING_DATADOG_PROFILER_LOG_LEVEL,
            ProfilingConfig.PROFILING_DATADOG_PROFILER_LOG_LEVEL_DEFAULT),
        ProfilingConfig.PROFILING_DATADOG_PROFILER_LOG_LEVEL_DEFAULT);
    appendConfig(
        sb,
        "DDProf Safe Mode",
        configProvider.getInteger(
            ProfilingConfig.PROFILING_DATADOG_PROFILER_SAFEMODE,
            ProfilingConfig.PROFILING_DATADOG_PROFILER_SAFEMODE_DEFAULT),
        ProfilingConfig.PROFILING_DATADOG_PROFILER_SAFEMODE_DEFAULT);
    appendConfig(
        sb,
        "DDProf Line Numbers",
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_DATADOG_PROFILER_LINE_NUMBERS,
            ProfilingConfig.PROFILING_DATADOG_PROFILER_LINE_NUMBERS_DEFAULT),
        ProfilingConfig.PROFILING_DATADOG_PROFILER_LINE_NUMBERS_DEFAULT);
    appendConfig(
        sb,
        "DDProf Stack Depth",
        configProvider.getString(ProfilingConfig.PROFILING_DATADOG_PROFILER_STACKDEPTH),
        null);
    appendConfig(
        sb,
        "DDProf C-Stack",
        configProvider.getString(
            ProfilingConfig.PROFILING_DATADOG_PROFILER_CSTACK,
            ProfilingConfig.PROFILING_DATADOG_PROFILER_CSTACK_DEFAULT),
        ProfilingConfig.PROFILING_DATADOG_PROFILER_CSTACK_DEFAULT);

    sb.append("\n=== DDProf CPU Profiling ===\n");
    appendConfig(
        sb,
        "DDProf CPU Enabled",
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_DATADOG_PROFILER_CPU_ENABLED,
            ProfilingConfig.PROFILING_DATADOG_PROFILER_CPU_ENABLED_DEFAULT),
        ProfilingConfig.PROFILING_DATADOG_PROFILER_CPU_ENABLED_DEFAULT);
    appendConfig(
        sb,
        "DDProf CPU Interval",
        configProvider.getInteger(
            ProfilingConfig.PROFILING_DATADOG_PROFILER_CPU_INTERVAL,
            ProfilingConfig.PROFILING_DATADOG_PROFILER_CPU_INTERVAL_DEFAULT),
        ProfilingConfig.PROFILING_DATADOG_PROFILER_CPU_INTERVAL_DEFAULT,
        " ms");

    sb.append("\n=== DDProf Wall Profiling ===\n");
    appendConfig(
        sb,
        "DDProf Wall Enabled",
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED,
            ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED_DEFAULT),
        ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED_DEFAULT);
    appendConfig(
        sb,
        "DDProf Wall Interval",
        configProvider.getInteger(
            ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_INTERVAL,
            ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_INTERVAL_DEFAULT),
        ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_INTERVAL_DEFAULT,
        " ms");
    appendConfig(
        sb,
        "DDProf Wall Collapsing",
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_COLLAPSING,
            ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_COLLAPSING_DEFAULT),
        ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_COLLAPSING_DEFAULT);
    appendConfig(
        sb,
        "DDProf Wall Context Filter",
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER,
            ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER_DEFAULT),
        ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_CONTEXT_FILTER_DEFAULT);
    appendConfig(
        sb,
        "DDProf Wall JVMTI",
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_JVMTI,
            ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_JVMTI_DEFAULT),
        ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_JVMTI_DEFAULT);

    sb.append("\n=== DDProf Allocation Profiling ===\n");
    appendConfig(
        sb,
        "DDProf Alloc Enabled",
        configProvider.getBoolean(ProfilingConfig.PROFILING_DATADOG_PROFILER_ALLOC_ENABLED, false),
        false);
    appendConfig(
        sb,
        "DDProf Alloc Interval",
        configProvider.getInteger(
            ProfilingConfig.PROFILING_DATADOG_PROFILER_ALLOC_INTERVAL,
            ProfilingConfig.PROFILING_DATADOG_PROFILER_ALLOC_INTERVAL_DEFAULT),
        ProfilingConfig.PROFILING_DATADOG_PROFILER_ALLOC_INTERVAL_DEFAULT,
        " bytes");

    sb.append("\n=== DDProf Live Heap Profiling ===\n");
    appendConfig(
        sb,
        "DDProf Live Heap Enabled",
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_ENABLED,
            ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_ENABLED_DEFAULT),
        ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_ENABLED_DEFAULT);
    appendConfig(
        sb,
        "DDProf Live Heap Interval",
        configProvider.getString(ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_INTERVAL),
        null);
    appendConfig(
        sb,
        "DDProf Live Heap Capacity",
        configProvider.getInteger(
            ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_CAPACITY,
            ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_CAPACITY_DEFAULT),
        ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_CAPACITY_DEFAULT);
    appendConfig(
        sb,
        "DDProf Live Heap Track Size",
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_TRACK_HEAPSIZE,
            ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_TRACK_HEAPSIZE_DEFAFULT),
        ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_TRACK_HEAPSIZE_DEFAFULT);
    appendConfig(
        sb,
        "DDProf Live Heap Sample Percent",
        configProvider.getInteger(
            ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_SAMPLE_PERCENT,
            ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_SAMPLE_PERCENT_DEFAULT),
        ProfilingConfig.PROFILING_DATADOG_PROFILER_LIVEHEAP_SAMPLE_PERCENT_DEFAULT,
        "%");

    sb.append("\n=== DDProf Scheduling ===\n");
    appendConfig(
        sb,
        "DDProf Scheduling Event",
        configProvider.getString(ProfilingConfig.PROFILING_DATADOG_PROFILER_SCHEDULING_EVENT),
        null);
    appendConfig(
        sb,
        "DDProf Scheduling Event Interval",
        configProvider.getString(
            ProfilingConfig.PROFILING_DATADOG_PROFILER_SCHEDULING_EVENT_INTERVAL),
        null);

    sb.append("\n=== Context & Timeline Settings ===\n");
    appendConfig(
        sb,
        "Context Attributes",
        configProvider.getString(ProfilingConfig.PROFILING_CONTEXT_ATTRIBUTES),
        null);
    appendConfig(
        sb,
        "Context Attributes Span Name Enabled",
        configProvider.getString(ProfilingConfig.PROFILING_CONTEXT_ATTRIBUTES_SPAN_NAME_ENABLED),
        null);
    appendConfig(
        sb,
        "Context Attributes Resource Name Enabled",
        configProvider.getString(
            ProfilingConfig.PROFILING_CONTEXT_ATTRIBUTES_RESOURCE_NAME_ENABLED),
        null);
    appendConfig(
        sb,
        "Timeline Events Enabled",
        config.isProfilingTimelineEventsEnabled(),
        ProfilingConfig.PROFILING_TIMELINE_EVENTS_ENABLED_DEFAULT);

    sb.append("\n=== Queueing Time Settings ===\n");
    appendConfig(
        sb,
        "Queueing Time Enabled",
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_QUEUEING_TIME_ENABLED,
            ProfilingConfig.PROFILING_QUEUEING_TIME_ENABLED_DEFAULT),
        ProfilingConfig.PROFILING_QUEUEING_TIME_ENABLED_DEFAULT);
    appendConfig(
        sb,
        "Queueing Time Threshold",
        configProvider.getLong(
            ProfilingConfig.PROFILING_QUEUEING_TIME_THRESHOLD_MILLIS,
            ProfilingConfig.PROFILING_QUEUEING_TIME_THRESHOLD_MILLIS_DEFAULT),
        ProfilingConfig.PROFILING_QUEUEING_TIME_THRESHOLD_MILLIS_DEFAULT,
        " ms");

    sb.append("\n=== SMAP Settings ===\n");
    appendConfig(
        sb,
        "SMAP Collection Enabled",
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_SMAP_COLLECTION_ENABLED,
            ProfilingConfig.PROFILING_SMAP_COLLECTION_ENABLED_DEFAULT),
        ProfilingConfig.PROFILING_SMAP_COLLECTION_ENABLED_DEFAULT);
    appendConfig(
        sb,
        "SMAP Aggregation Enabled",
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_SMAP_AGGREGATION_ENABLED,
            ProfilingConfig.PROFILING_SMAP_AGGREGATION_ENABLED_DEFAULT),
        ProfilingConfig.PROFILING_SMAP_AGGREGATION_ENABLED_DEFAULT);

    sb.append("\n=== Miscellaneous Settings ===\n");
    appendConfig(
        sb,
        "Stack Depth",
        configProvider.getInteger(
            ProfilingConfig.PROFILING_STACKDEPTH, ProfilingConfig.PROFILING_STACKDEPTH_DEFAULT),
        ProfilingConfig.PROFILING_STACKDEPTH_DEFAULT);
    appendConfig(
        sb,
        "Auxiliary Profiler",
        configProvider.getString(
            ProfilingConfig.PROFILING_AUXILIARY_TYPE,
            ProfilingConfig.PROFILING_AUXILIARY_TYPE_DEFAULT),
        ProfilingConfig.PROFILING_AUXILIARY_TYPE_DEFAULT);
    appendConfig(sb, "Exclude Agent Threads", config.isProfilingExcludeAgentThreads(), false);
    appendConfig(
        sb,
        "Hotspots Enabled",
        configProvider.getBoolean(ProfilingConfig.PROFILING_HOTSPOTS_ENABLED, false),
        false);
    appendConfig(
        sb,
        "Endpoint Collection Enabled",
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_ENDPOINT_COLLECTION_ENABLED,
            ProfilingConfig.PROFILING_ENDPOINT_COLLECTION_ENABLED_DEFAULT),
        ProfilingConfig.PROFILING_ENDPOINT_COLLECTION_ENABLED_DEFAULT);
    appendConfig(
        sb,
        "Temp Directory",
        configProvider.getString(
            ProfilingConfig.PROFILING_TEMP_DIR, ProfilingConfig.PROFILING_TEMP_DIR_DEFAULT),
        ProfilingConfig.PROFILING_TEMP_DIR_DEFAULT);
    appendConfig(
        sb,
        "Debug Dump Path",
        configProvider.getString(ProfilingConfig.PROFILING_DEBUG_DUMP_PATH),
        null);

    sb.append("Profiling Tags: ");
    Map<String, String> tags = config.getMergedProfilingTags();
    if (tags != null && !tags.isEmpty()) {
      sb.append(tags);
    } else {
      sb.append("null");
    }
    sb.append(" (default: null)\n");

    sb.append("Final Profiling URL: ")
        .append(config.getFinalProfilingUrl())
        .append(" (default: varies)\n");

    return sb.toString();
  }

  private void appendConfig(StringBuilder sb, String name, Object value, Object defaultValue) {
    // The suffix is there for showing time units & the like; ideally we'd derive this
    // programmatically
    // from the different config sources.
    appendConfig(sb, name, value, defaultValue, "");
  }

  private void appendConfig(
      StringBuilder sb, String name, Object value, Object defaultValue, String suffix) {
    sb.append(name).append(": ");

    if (value != null) {
      sb.append(value);
      if (!suffix.isEmpty()) {
        sb.append(" ");
        sb.append(suffix);
      }
    } else {
      sb.append("null");
    }

    sb.append(" (default: ");
    if (defaultValue != null) {
      sb.append(defaultValue);
      if (!suffix.isEmpty() && !"null".equals(defaultValue.toString())) {
        sb.append(suffix.trim());
      }
    } else {
      sb.append("null");
    }
    sb.append(")\n");
  }
}
