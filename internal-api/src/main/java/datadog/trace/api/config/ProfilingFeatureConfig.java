package datadog.trace.api.config;

import static datadog.trace.api.DDTags.HOST_TAG;
import static datadog.trace.api.DDTags.LANGUAGE_TAG_KEY;
import static datadog.trace.api.DDTags.LANGUAGE_TAG_VALUE;
import static datadog.trace.api.DDTags.RUNTIME_VERSION_TAG;
import static datadog.trace.api.DDTags.SERVICE_TAG;
import static datadog.trace.api.config.GeneralFeatureConfig.newHashMap;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_AGENTLESS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_AGENTLESS_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ASYNC_ALLOC_ENABLED_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ASYNC_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_SAMPLE_LIMIT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_SAMPLE_LIMIT_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCLUDE_AGENT_THREADS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_HOTSPOTS_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_LEGACY_TRACING_INTEGRATION;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_LEGACY_TRACING_INTEGRATION_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_PROXY_HOST;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_PROXY_PASSWORD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_PROXY_PORT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_PROXY_PORT_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_PROXY_USERNAME;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_START_DELAY;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_START_DELAY_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_START_FORCE_FIRST;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_START_FORCE_FIRST_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_TAGS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_COMPRESSION;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_COMPRESSION_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_PERIOD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_PERIOD_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_SUMMARY_ON_413;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_SUMMARY_ON_413_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_TIMEOUT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_TIMEOUT_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_URL;

import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Collections;
import java.util.Map;

public class ProfilingFeatureConfig extends AbstractFeatureConfig {
  private final GeneralFeatureConfig generalConfig;
  private final TracerFeatureConfig tracerConfig;
  private final boolean profilingEnabled;
  private final boolean profilingAgentless;
  private final boolean profilingLegacyTracingIntegrationEnabled;
  private final boolean asyncProfilerEnabled;
  @Deprecated private final String profilingUrl;
  private final Map<String, String> profilingTags;
  private final int profilingStartDelay;
  private final boolean profilingStartForceFirst;
  private final int profilingUploadPeriod;
  private final String profilingTemplateOverrideFile;
  private final int profilingUploadTimeout;
  private final String profilingUploadCompression;
  private final String profilingProxyHost;
  private final int profilingProxyPort;
  private final String profilingProxyUsername;
  private final String profilingProxyPassword;
  private final int profilingExceptionSampleLimit;
  private final int profilingExceptionHistogramTopItems;
  private final int profilingExceptionHistogramMaxCollectionSize;
  private final boolean profilingExcludeAgentThreads;
  private final boolean profilingHotspotsEnabled;
  private final boolean profilingUploadSummaryOn413Enabled;

  public ProfilingFeatureConfig(
      ConfigProvider configProvider,
      GeneralFeatureConfig generalConfig,
      TracerFeatureConfig tracerConfig) {
    super(configProvider);
    this.generalConfig = generalConfig;
    this.tracerConfig = tracerConfig;
    this.profilingEnabled = configProvider.getBoolean(PROFILING_ENABLED, PROFILING_ENABLED_DEFAULT);
    this.profilingAgentless =
        configProvider.getBoolean(PROFILING_AGENTLESS, PROFILING_AGENTLESS_DEFAULT);
    this.profilingLegacyTracingIntegrationEnabled =
        configProvider.getBoolean(
            PROFILING_LEGACY_TRACING_INTEGRATION, PROFILING_LEGACY_TRACING_INTEGRATION_DEFAULT);
    this.asyncProfilerEnabled =
        configProvider.getBoolean(PROFILING_ASYNC_ENABLED, PROFILING_ASYNC_ALLOC_ENABLED_DEFAULT);
    this.profilingUrl = configProvider.getString(PROFILING_URL);

    this.profilingTags = configProvider.getMergedMap(PROFILING_TAGS);
    this.profilingStartDelay =
        configProvider.getInteger(PROFILING_START_DELAY, PROFILING_START_DELAY_DEFAULT);
    this.profilingStartForceFirst =
        configProvider.getBoolean(PROFILING_START_FORCE_FIRST, PROFILING_START_FORCE_FIRST_DEFAULT);
    this.profilingUploadPeriod =
        configProvider.getInteger(PROFILING_UPLOAD_PERIOD, PROFILING_UPLOAD_PERIOD_DEFAULT);
    this.profilingTemplateOverrideFile = configProvider.getString(PROFILING_TEMPLATE_OVERRIDE_FILE);
    this.profilingUploadTimeout =
        configProvider.getInteger(PROFILING_UPLOAD_TIMEOUT, PROFILING_UPLOAD_TIMEOUT_DEFAULT);
    this.profilingUploadCompression =
        configProvider.getString(
            PROFILING_UPLOAD_COMPRESSION, PROFILING_UPLOAD_COMPRESSION_DEFAULT);
    this.profilingProxyHost = configProvider.getString(PROFILING_PROXY_HOST);
    this.profilingProxyPort =
        configProvider.getInteger(PROFILING_PROXY_PORT, PROFILING_PROXY_PORT_DEFAULT);
    this.profilingProxyUsername = configProvider.getString(PROFILING_PROXY_USERNAME);
    this.profilingProxyPassword = configProvider.getString(PROFILING_PROXY_PASSWORD);

    this.profilingExceptionSampleLimit =
        configProvider.getInteger(
            PROFILING_EXCEPTION_SAMPLE_LIMIT, PROFILING_EXCEPTION_SAMPLE_LIMIT_DEFAULT);
    this.profilingExceptionHistogramTopItems =
        configProvider.getInteger(
            PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS,
            PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS_DEFAULT);
    this.profilingExceptionHistogramMaxCollectionSize =
        configProvider.getInteger(
            PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE,
            PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE_DEFAULT);

    this.profilingExcludeAgentThreads =
        configProvider.getBoolean(PROFILING_EXCLUDE_AGENT_THREADS, true);

    // code hotspots are disabled by default because of potential perf overhead they can incur
    this.profilingHotspotsEnabled = configProvider.getBoolean(PROFILING_HOTSPOTS_ENABLED, false);

    this.profilingUploadSummaryOn413Enabled =
        configProvider.getBoolean(
            PROFILING_UPLOAD_SUMMARY_ON_413, PROFILING_UPLOAD_SUMMARY_ON_413_DEFAULT);
  }

  public boolean isProfilingEnabled() {
    return this.profilingEnabled;
  }

  public boolean isProfilingAgentless() {
    return this.profilingAgentless;
  }

  public int getProfilingStartDelay() {
    return this.profilingStartDelay;
  }

  public boolean isProfilingStartForceFirst() {
    return this.profilingStartForceFirst;
  }

  public int getProfilingUploadPeriod() {
    return this.profilingUploadPeriod;
  }

  public String getProfilingTemplateOverrideFile() {
    return this.profilingTemplateOverrideFile;
  }

  public int getProfilingUploadTimeout() {
    return this.profilingUploadTimeout;
  }

  public String getProfilingUploadCompression() {
    return this.profilingUploadCompression;
  }

  public String getProfilingProxyHost() {
    return this.profilingProxyHost;
  }

  public int getProfilingProxyPort() {
    return this.profilingProxyPort;
  }

  public String getProfilingProxyUsername() {
    return this.profilingProxyUsername;
  }

  public String getProfilingProxyPassword() {
    return this.profilingProxyPassword;
  }

  public int getProfilingExceptionSampleLimit() {
    return this.profilingExceptionSampleLimit;
  }

  public int getProfilingExceptionHistogramTopItems() {
    return this.profilingExceptionHistogramTopItems;
  }

  public int getProfilingExceptionHistogramMaxCollectionSize() {
    return this.profilingExceptionHistogramMaxCollectionSize;
  }

  public boolean isProfilingExcludeAgentThreads() {
    return this.profilingExcludeAgentThreads;
  }

  public boolean isProfilingHotspotsEnabled() {
    return this.profilingHotspotsEnabled;
  }

  public boolean isProfilingUploadSummaryOn413Enabled() {
    return this.profilingUploadSummaryOn413Enabled;
  }

  public boolean isProfilingLegacyTracingIntegrationEnabled() {
    return this.profilingLegacyTracingIntegrationEnabled;
  }

  public boolean isAsyncProfilerEnabled() {
    return this.asyncProfilerEnabled;
  }

  public String getFinalProfilingUrl() {
    if (this.profilingUrl != null) {
      // when profilingUrl is set we use it regardless of apiKey/agentless config
      return this.profilingUrl;
    } else if (this.profilingAgentless) {
      // when agentless profiling is turned on we send directly to our intake
      return "https://intake.profile." + this.generalConfig.getSite() + "/api/v2/profile";
    } else {
      // when profilingUrl and agentless are not set we send to the dd trace agent running locally
      return "http://"
          + this.tracerConfig.getAgentHost()
          + ":"
          + this.tracerConfig.getAgentPort()
          + "/profiling/v1/input";
    }
  }

  public Map<String, String> getMergedProfilingTags() {
    final Map<String, String> runtimeTags = this.generalConfig.getRuntimeTags();
    final Map<String, String> globalTags = this.generalConfig.getGlobalTags();
    final String host = this.generalConfig.getHostName();
    final Map<String, String> result =
        newHashMap(
            globalTags.size()
                + this.profilingTags.size()
                + runtimeTags.size()
                + 4 /* for serviceName and host and language and runtime_version */);
    result.put(HOST_TAG, host); // Host goes first to allow to override it
    result.putAll(globalTags);
    result.putAll(this.profilingTags);
    result.putAll(runtimeTags);
    // service name set here instead of getRuntimeTags because apm already manages the service tag
    // and may choose to override it.
    result.put(SERVICE_TAG, this.generalConfig.getServiceName());
    result.put(LANGUAGE_TAG_KEY, LANGUAGE_TAG_VALUE);
    result.put(RUNTIME_VERSION_TAG, this.generalConfig.getRuntimeVersion());
    return Collections.unmodifiableMap(result);
  }

  @Override
  public String toString() {
    return "ProfilingFeatureConfig{"
        + "generalConfig="
        + this.generalConfig
        + ", tracerConfig="
        + this.tracerConfig
        + ", profilingEnabled="
        + this.profilingEnabled
        + ", profilingAgentless="
        + this.profilingAgentless
        + ", profilingLegacyTracingIntegrationEnabled="
        + this.profilingLegacyTracingIntegrationEnabled
        + ", asyncProfilerEnabled="
        + this.asyncProfilerEnabled
        + ", profilingUrl='"
        + this.profilingUrl
        + '\''
        + ", profilingTags="
        + this.profilingTags
        + ", profilingStartDelay="
        + this.profilingStartDelay
        + ", profilingStartForceFirst="
        + this.profilingStartForceFirst
        + ", profilingUploadPeriod="
        + this.profilingUploadPeriod
        + ", profilingTemplateOverrideFile='"
        + this.profilingTemplateOverrideFile
        + '\''
        + ", profilingUploadTimeout="
        + this.profilingUploadTimeout
        + ", profilingUploadCompression='"
        + this.profilingUploadCompression
        + '\''
        + ", profilingProxyHost='"
        + this.profilingProxyHost
        + '\''
        + ", profilingProxyPort="
        + this.profilingProxyPort
        + ", profilingProxyUsername='"
        + this.profilingProxyUsername
        + '\''
        + ", profilingProxyPassword="
        + (this.profilingProxyPassword == null ? "null" : "****")
        + ", profilingExceptionSampleLimit="
        + this.profilingExceptionSampleLimit
        + ", profilingExceptionHistogramTopItems="
        + this.profilingExceptionHistogramTopItems
        + ", profilingExceptionHistogramMaxCollectionSize="
        + this.profilingExceptionHistogramMaxCollectionSize
        + ", profilingExcludeAgentThreads="
        + this.profilingExcludeAgentThreads
        + ", profilingHotspotsEnabled="
        + this.profilingHotspotsEnabled
        + ", profilingUploadSummaryOn413Enabled="
        + this.profilingUploadSummaryOn413Enabled
        + '}';
  }
}
