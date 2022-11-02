package datadog.trace.api;

import static datadog.trace.api.config.GeneralConfig.TRACER_METRICS_IGNORED_RESOURCES;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableSet;

import datadog.trace.api.config.AppSecFeatureConfig;
import datadog.trace.api.config.CiVisibilityFeatureConfig;
import datadog.trace.api.config.CrashTrackingFeatureConfig;
import datadog.trace.api.config.CwsFeatureConfig;
import datadog.trace.api.config.DebuggerFeatureConfig;
import datadog.trace.api.config.GeneralFeatureConfig;
import datadog.trace.api.config.IastFeatureConfig;
import datadog.trace.api.config.JmxFetchFeatureConfig;
import datadog.trace.api.config.ProfilingFeatureConfig;
import datadog.trace.api.config.RemoteFeatureConfig;
import datadog.trace.api.config.TraceInstrumentationFeatureConfig;
import datadog.trace.api.config.TracerFeatureConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Config reads values with the following priority: 1) system properties, 2) environment variables,
 * 3) optional configuration file, 4) platform dependant properties. It also includes default values
 * to ensure a valid config.
 *
 * <p>
 */
@Deprecated
public class Config {
  private static final Logger LOGGER = LoggerFactory.getLogger(Config.class);
  private final GeneralFeatureConfig generalConfig;
  private final TracerFeatureConfig tracerConfig;
  private final IastFeatureConfig iastConfig;
  private final JmxFetchFeatureConfig jmxFetchConfig;
  private final TraceInstrumentationFeatureConfig traceInstrumentationConfig;
  private final ProfilingFeatureConfig profilingConfig;
  private final CrashTrackingFeatureConfig crashTrackingConfig;
  private final AppSecFeatureConfig appSecConfig;
  private final CiVisibilityFeatureConfig ciVisibilityConfig;
  private final RemoteFeatureConfig remoteConfig;
  private final DebuggerFeatureConfig debuggerConfig;
  private final CwsFeatureConfig cwsConfig;

  private final String configFileStatus;
  private final ConfigProvider configProvider;

  // Read order: System Properties -> Env Variables, [-> properties file], [-> default value]
  private Config() {
    this(ConfigProvider.createDefault());
  }

  private Config(final ConfigProvider configProvider) {
    this.configProvider = configProvider;
    this.generalConfig = new GeneralFeatureConfig(configProvider);
    this.tracerConfig = new TracerFeatureConfig(configProvider);
    this.iastConfig = new IastFeatureConfig(configProvider);
    this.jmxFetchConfig =
        new JmxFetchFeatureConfig(configProvider, this.generalConfig, this.tracerConfig);
    this.traceInstrumentationConfig = new TraceInstrumentationFeatureConfig(configProvider);
    this.profilingConfig =
        new ProfilingFeatureConfig(configProvider, this.generalConfig, this.tracerConfig);
    this.crashTrackingConfig =
        new CrashTrackingFeatureConfig(configProvider, this.generalConfig, this.tracerConfig);
    this.appSecConfig = new AppSecFeatureConfig(configProvider);
    this.ciVisibilityConfig = new CiVisibilityFeatureConfig(configProvider);
    this.remoteConfig = new RemoteFeatureConfig(configProvider);
    this.debuggerConfig = new DebuggerFeatureConfig(configProvider, this.tracerConfig);
    this.cwsConfig = new CwsFeatureConfig(configProvider);
    this.configFileStatus = configProvider.getConfigFileStatus();

    if (this.profilingConfig.isProfilingAgentless() && this.generalConfig.getApiKey() == null) {
      LOGGER.warn(
          "Agentless profiling activated but no api key provided. Profile uploading will likely fail");
    }

    LOGGER.debug("New instance: {}", this);
  }

  public GeneralFeatureConfig getGeneralConfig() {
    return this.generalConfig;
  }

  public TracerFeatureConfig getTracerConfig() {
    return this.tracerConfig;
  }

  public IastFeatureConfig getIastConfig() {
    return this.iastConfig;
  }

  public JmxFetchFeatureConfig getJmxFetchConfig() {
    return this.jmxFetchConfig;
  }

  public TraceInstrumentationFeatureConfig getTraceInstrumentationConfig() {
    return this.traceInstrumentationConfig;
  }

  public ProfilingFeatureConfig getProfilingConfig() {
    return this.profilingConfig;
  }

  public CrashTrackingFeatureConfig getCrashTrackingConfig() {
    return this.crashTrackingConfig;
  }

  public AppSecFeatureConfig getAppSecConfig() {
    return this.appSecConfig;
  }

  public CiVisibilityFeatureConfig getCiVisibilityConfig() {
    return this.ciVisibilityConfig;
  }

  public RemoteFeatureConfig getRemoteConfig() {
    return this.remoteConfig;
  }

  public DebuggerFeatureConfig getDebuggerConfig() {
    return this.debuggerConfig;
  }

  public CwsFeatureConfig getCwsConfig() {
    return this.cwsConfig;
  }

  public long getStartTimeMillis() {
    return this.generalConfig.getStartTimeMillis();
  }

  public String getRuntimeId() {
    return this.generalConfig.getRuntimeId();
  }

  public String getRuntimeVersion() {
    return this.generalConfig.getRuntimeVersion();
  }

  public String getApiKey() {
    return this.generalConfig.getApiKey();
  }

  public String getSite() {
    return this.generalConfig.getSite();
  }

  public String getHostName() {
    return this.generalConfig.getHostName();
  }

  public String getServiceName() {
    return this.generalConfig.getServiceName();
  }

  public boolean isServiceNameSetByUser() {
    return this.generalConfig.isServiceNameSetByUser();
  }

  public String getRootContextServiceName() {
    return this.traceInstrumentationConfig.getRootContextServiceName();
  }

  public boolean isTraceEnabled() {
    return this.traceInstrumentationConfig.isTraceEnabled();
  }

  public boolean isIntegrationsEnabled() {
    return this.traceInstrumentationConfig.isIntegrationsEnabled();
  }

  public boolean isIntegrationSynapseLegacyOperationName() {
    return this.traceInstrumentationConfig.isIntegrationSynapseLegacyOperationName();
  }

  public String getWriterType() {
    return this.tracerConfig.getWriterType();
  }

  public boolean isAgentConfiguredUsingDefault() {
    return this.tracerConfig.isAgentConfiguredUsingDefault();
  }

  public String getAgentUrl() {
    return this.tracerConfig.getAgentUrl();
  }

  public String getAgentHost() {
    return this.tracerConfig.getAgentHost();
  }

  public int getAgentPort() {
    return this.tracerConfig.getAgentPort();
  }

  public String getAgentUnixDomainSocket() {
    return this.tracerConfig.getAgentUnixDomainSocket();
  }

  public String getAgentNamedPipe() {
    return this.tracerConfig.getAgentNamedPipe();
  }

  public int getAgentTimeout() {
    return this.tracerConfig.getAgentTimeout();
  }

  public Set<String> getNoProxyHosts() {
    return this.tracerConfig.getNoProxyHosts();
  }

  public boolean isPrioritySamplingEnabled() {
    return this.tracerConfig.isPrioritySamplingEnabled();
  }

  public String getPrioritySamplingForce() {
    return this.tracerConfig.getPrioritySamplingForce();
  }

  public boolean isTraceResolverEnabled() {
    return this.tracerConfig.isTraceResolverEnabled();
  }

  public Set<String> getIastWeakHashAlgorithms() {
    return this.iastConfig.getIastWeakHashAlgorithms();
  }

  public Pattern getIastWeakCipherAlgorithms() {
    return this.iastConfig.getIastWeakCipherAlgorithms();
  }

  public boolean isIastDeduplicationEnabled() {
    return this.iastConfig.isIastDeduplicationEnabled();
  }

  public Map<String, String> getServiceMapping() {
    return this.tracerConfig.getServiceMapping();
  }

  public List<String> getExcludedClasses() {
    return this.traceInstrumentationConfig.getExcludedClasses();
  }

  public String getExcludedClassesFile() {
    return this.traceInstrumentationConfig.getExcludedClassesFile();
  }

  public Set<String> getExcludedClassLoaders() {
    return this.traceInstrumentationConfig.getExcludedClassLoaders();
  }

  public List<String> getExcludedCodeSources() {
    return this.traceInstrumentationConfig.getExcludedCodeSources();
  }

  public Map<String, String> getRequestHeaderTags() {
    return this.tracerConfig.getRequestHeaderTags();
  }

  public Map<String, String> getResponseHeaderTags() {
    return this.tracerConfig.getResponseHeaderTags();
  }

  public Map<String, String> getHttpServerPathResourceNameMapping() {
    return this.tracerConfig.getHttpServerPathResourceNameMapping();
  }

  public BitSet getHttpServerErrorStatuses() {
    return this.tracerConfig.getHttpServerErrorStatuses();
  }

  public BitSet getHttpClientErrorStatuses() {
    return this.tracerConfig.getHttpClientErrorStatuses();
  }

  public boolean isHttpServerTagQueryString() {
    return this.traceInstrumentationConfig.isHttpServerTagQueryString();
  }

  public boolean isHttpServerRawQueryString() {
    return this.traceInstrumentationConfig.isHttpServerRawQueryString();
  }

  public boolean isHttpServerRawResource() {
    return this.traceInstrumentationConfig.isHttpServerRawResource();
  }

  public boolean isHttpServerRouteBasedNaming() {
    return this.traceInstrumentationConfig.isHttpServerRouteBasedNaming();
  }

  public boolean isHttpClientTagQueryString() {
    return this.traceInstrumentationConfig.isHttpClientTagQueryString();
  }

  public boolean isHttpClientSplitByDomain() {
    return this.traceInstrumentationConfig.isHttpClientSplitByDomain();
  }

  public boolean isDbClientSplitByInstance() {
    return this.traceInstrumentationConfig.isDbClientSplitByInstance();
  }

  public boolean isDbClientSplitByInstanceTypeSuffix() {
    return this.traceInstrumentationConfig.isDbClientSplitByInstanceTypeSuffix();
  }

  public Set<String> getSplitByTags() {
    return this.tracerConfig.getSplitByTags();
  }

  public int getScopeDepthLimit() {
    return this.tracerConfig.getScopeDepthLimit();
  }

  public boolean isScopeStrictMode() {
    return this.tracerConfig.isScopeStrictMode();
  }

  public boolean isScopeInheritAsyncPropagation() {
    return this.tracerConfig.isScopeInheritAsyncPropagation();
  }

  public int getScopeIterationKeepAlive() {
    return this.tracerConfig.getScopeIterationKeepAlive();
  }

  public int getPartialFlushMinSpans() {
    return this.tracerConfig.getPartialFlushMinSpans();
  }

  public boolean isTraceStrictWritesEnabled() {
    return this.tracerConfig.isTraceStrictWritesEnabled();
  }

  public boolean isRuntimeContextFieldInjection() {
    return this.traceInstrumentationConfig.isRuntimeContextFieldInjection();
  }

  public boolean isSerialVersionUIDFieldInjection() {
    return this.traceInstrumentationConfig.isSerialVersionUIDFieldInjection();
  }

  public boolean isLogExtractHeaderNames() {
    return this.tracerConfig.isLogExtractHeaderNames();
  }

  public Set<PropagationStyle> getPropagationStylesToExtract() {
    return this.tracerConfig.getPropagationStylesToExtract();
  }

  public Set<PropagationStyle> getPropagationStylesToInject() {
    return this.tracerConfig.getPropagationStylesToInject();
  }

  public int getClockSyncPeriod() {
    return this.tracerConfig.getClockSyncPeriod();
  }

  public String getDogStatsDNamedPipe() {
    return this.generalConfig.getDogStatsDNamedPipe();
  }

  public int getDogStatsDStartDelay() {
    return this.generalConfig.getDogStatsDStartDelay();
  }

  public boolean isJmxFetchEnabled() {
    return this.jmxFetchConfig.isJmxFetchEnabled();
  }

  public String getJmxFetchConfigDir() {
    return this.jmxFetchConfig.getJmxFetchConfigDir();
  }

  public List<String> getJmxFetchConfigs() {
    return this.jmxFetchConfig.getJmxFetchConfigs();
  }

  public List<String> getJmxFetchMetricsConfigs() {
    return this.jmxFetchConfig.getJmxFetchMetricsConfigs();
  }

  public Integer getJmxFetchCheckPeriod() {
    return this.jmxFetchConfig.getJmxFetchCheckPeriod();
  }

  public Integer getJmxFetchRefreshBeansPeriod() {
    return this.jmxFetchConfig.getJmxFetchRefreshBeansPeriod();
  }

  public Integer getJmxFetchInitialRefreshBeansPeriod() {
    return this.jmxFetchConfig.getJmxFetchInitialRefreshBeansPeriod();
  }

  public String getJmxFetchStatsdHost() {
    return this.jmxFetchConfig.getJmxFetchStatsdHost();
  }

  public Integer getJmxFetchStatsdPort() {
    return this.jmxFetchConfig.getJmxFetchStatsdPort();
  }

  public boolean isJmxFetchMultipleRuntimeServicesEnabled() {
    return this.jmxFetchConfig.isJmxFetchMultipleRuntimeServicesEnabled();
  }

  public int getJmxFetchMultipleRuntimeServicesLimit() {
    return this.jmxFetchConfig.getJmxFetchMultipleRuntimeServicesLimit();
  }

  public boolean isHealthMetricsEnabled() {
    return this.generalConfig.isHealthMetricsEnabled();
  }

  public String getHealthMetricsStatsdHost() {
    return this.generalConfig.getHealthMetricsStatsdHost();
  }

  public Integer getHealthMetricsStatsdPort() {
    return this.generalConfig.getHealthMetricsStatsdPort();
  }

  public boolean isPerfMetricsEnabled() {
    return this.generalConfig.isPerfMetricsEnabled();
  }

  public boolean isTracerMetricsEnabled() {
    return this.generalConfig.isTracerMetricsEnabled();
  }

  public boolean isTracerMetricsBufferingEnabled() {
    return this.generalConfig.isTracerMetricsBufferingEnabled();
  }

  public int getTracerMetricsMaxAggregates() {
    return this.generalConfig.getTracerMetricsMaxAggregates();
  }

  public int getTracerMetricsMaxPending() {
    return this.generalConfig.getTracerMetricsMaxPending();
  }

  public boolean isLogsInjectionEnabled() {
    return this.traceInstrumentationConfig.isLogsInjectionEnabled();
  }

  public boolean isLogsMDCTagsInjectionEnabled() {
    return this.traceInstrumentationConfig.isLogsMDCTagsInjectionEnabled();
  }

  public boolean isReportHostName() {
    return this.generalConfig.isReportHostName();
  }

  public String getTraceAnnotations() {
    return this.traceInstrumentationConfig.getTraceAnnotations();
  }

  public String getTraceMethods() {
    return this.traceInstrumentationConfig.getTraceMethods();
  }

  public boolean isTraceExecutorsAll() {
    return this.traceInstrumentationConfig.isTraceExecutorsAll();
  }

  public List<String> getTraceExecutors() {
    return this.traceInstrumentationConfig.getTraceExecutors();
  }

  public Set<String> getTraceThreadPoolExecutorsExclude() {
    return this.traceInstrumentationConfig.getTraceThreadPoolExecutorsExclude();
  }

  public boolean isTraceAnalyticsEnabled() {
    return this.tracerConfig.isTraceAnalyticsEnabled();
  }

  public String getTraceClientIpHeader() {
    return this.tracerConfig.getTraceClientIpHeader();
  }

  public boolean isTraceClientIpResolverEnabled() {
    return this.tracerConfig.isTraceClientIpResolverEnabled();
  }

  public boolean isClientIpEnabled() {
    return this.tracerConfig.isClientIpEnabled();
  }

  public Map<String, String> getTraceSamplingServiceRules() {
    return this.tracerConfig.getTraceSamplingServiceRules();
  }

  public Map<String, String> getTraceSamplingOperationRules() {
    return this.tracerConfig.getTraceSamplingOperationRules();
  }

  public String getTraceSamplingRules() {
    return this.tracerConfig.getTraceSamplingRules();
  }

  public Double getTraceSampleRate() {
    return this.tracerConfig.getTraceSampleRate();
  }

  public int getTraceRateLimit() {
    return this.tracerConfig.getTraceRateLimit();
  }

  public boolean isProfilingEnabled() {
    return this.profilingConfig.isProfilingEnabled();
  }

  public boolean isProfilingAgentless() {
    return this.profilingConfig.isProfilingAgentless();
  }

  public int getProfilingStartDelay() {
    return this.profilingConfig.getProfilingStartDelay();
  }

  public boolean isProfilingStartForceFirst() {
    return this.profilingConfig.isProfilingStartForceFirst();
  }

  public int getProfilingUploadPeriod() {
    return this.profilingConfig.getProfilingUploadPeriod();
  }

  public String getProfilingTemplateOverrideFile() {
    return this.profilingConfig.getProfilingTemplateOverrideFile();
  }

  public int getProfilingUploadTimeout() {
    return this.profilingConfig.getProfilingUploadTimeout();
  }

  public String getProfilingUploadCompression() {
    return this.profilingConfig.getProfilingUploadCompression();
  }

  public String getProfilingProxyHost() {
    return this.profilingConfig.getProfilingProxyHost();
  }

  public int getProfilingProxyPort() {
    return this.profilingConfig.getProfilingProxyPort();
  }

  public String getProfilingProxyUsername() {
    return this.profilingConfig.getProfilingProxyUsername();
  }

  public String getProfilingProxyPassword() {
    return this.profilingConfig.getProfilingProxyPassword();
  }

  public int getProfilingExceptionSampleLimit() {
    return this.profilingConfig.getProfilingExceptionSampleLimit();
  }

  public int getProfilingExceptionHistogramTopItems() {
    return this.profilingConfig.getProfilingExceptionHistogramTopItems();
  }

  public int getProfilingExceptionHistogramMaxCollectionSize() {
    return this.profilingConfig.getProfilingExceptionHistogramMaxCollectionSize();
  }

  public boolean isProfilingExcludeAgentThreads() {
    return this.profilingConfig.isProfilingExcludeAgentThreads();
  }

  public boolean isProfilingHotspotsEnabled() {
    return this.profilingConfig.isProfilingHotspotsEnabled();
  }

  public boolean isProfilingUploadSummaryOn413Enabled() {
    return this.profilingConfig.isProfilingUploadSummaryOn413Enabled();
  }

  public boolean isProfilingLegacyTracingIntegrationEnabled() {
    return this.profilingConfig.isProfilingLegacyTracingIntegrationEnabled();
  }

  public boolean isAsyncProfilerEnabled() {
    return this.profilingConfig.isAsyncProfilerEnabled();
  }

  public boolean isCrashTrackingAgentless() {
    return this.crashTrackingConfig.isCrashTrackingAgentless();
  }

  public boolean isTelemetryEnabled() {
    return this.generalConfig.isTelemetryEnabled();
  }

  public int getTelemetryHeartbeatInterval() {
    return this.generalConfig.getTelemetryHeartbeatInterval();
  }

  public ProductActivationConfig getAppSecEnabledConfig() {
    return this.appSecConfig.getAppSecEnabledConfig();
  }

  public boolean isAppSecReportingInband() {
    return this.appSecConfig.isAppSecReportingInband();
  }

  public int getAppSecReportMinTimeout() {
    return this.appSecConfig.getAppSecReportMinTimeout();
  }

  public int getAppSecReportMaxTimeout() {
    return this.appSecConfig.getAppSecReportMaxTimeout();
  }

  public int getAppSecTraceRateLimit() {
    return this.appSecConfig.getAppSecTraceRateLimit();
  }

  public boolean isAppSecWafMetrics() {
    return this.appSecConfig.isAppSecWafMetrics();
  }

  public String getAppSecObfuscationParameterKeyRegexp() {
    return this.appSecConfig.getAppSecObfuscationParameterKeyRegexp();
  }

  public String getAppSecObfuscationParameterValueRegexp() {
    return this.appSecConfig.getAppSecObfuscationParameterValueRegexp();
  }

  public String getAppSecHttpBlockedTemplateHtml() {
    return this.appSecConfig.getAppSecHttpBlockedTemplateHtml();
  }

  public String getAppSecHttpBlockedTemplateJson() {
    return this.appSecConfig.getAppSecHttpBlockedTemplateJson();
  }

  public boolean isIastEnabled() {
    return this.iastConfig.isIastEnabled();
  }

  public int getIastMaxConcurrentRequests() {
    return this.iastConfig.getIastMaxConcurrentRequests();
  }

  public int getIastVulnerabilitiesPerRequest() {
    return this.iastConfig.getIastVulnerabilitiesPerRequest();
  }

  public float getIastRequestSampling() {
    return this.iastConfig.getIastRequestSampling();
  }

  public boolean isCiVisibilityEnabled() {
    return this.ciVisibilityConfig.isCiVisibilityEnabled();
  }

  public boolean isCiVisibilityAgentlessEnabled() {
    return this.ciVisibilityConfig.isCiVisibilityAgentlessEnabled();
  }

  public String getCiVisibilityAgentlessUrl() {
    return this.ciVisibilityConfig.getCiVisibilityAgentlessUrl();
  }

  public String getAppSecRulesFile() {
    return this.appSecConfig.getAppSecRulesFile();
  }

  public long getRemoteConfigMaxPayloadSizeBytes() {
    return this.remoteConfig.getRemoteConfigMaxPayloadSizeBytes();
  }

  public boolean isRemoteConfigEnabled() {
    return this.remoteConfig.isRemoteConfigEnabled();
  }

  public boolean isRemoteConfigIntegrityCheckEnabled() {
    return this.remoteConfig.isRemoteConfigIntegrityCheckEnabled();
  }

  public String getFinalRemoteConfigUrl() {
    return this.remoteConfig.getFinalRemoteConfigUrl();
  }

  public int getRemoteConfigInitialPollInterval() {
    return this.remoteConfig.getRemoteConfigInitialPollInterval();
  }

  public String getRemoteConfigTargetsKeyId() {
    return this.remoteConfig.getRemoteConfigTargetsKeyId();
  }

  public String getRemoteConfigTargetsKey() {
    return this.remoteConfig.getRemoteConfigTargetsKey();
  }

  public boolean isDebuggerEnabled() {
    return this.debuggerConfig.isDebuggerEnabled();
  }

  public int getDebuggerUploadTimeout() {
    return this.debuggerConfig.getDebuggerUploadTimeout();
  }

  public int getDebuggerUploadFlushInterval() {
    return this.debuggerConfig.getDebuggerUploadFlushInterval();
  }

  public boolean isDebuggerClassFileDumpEnabled() {
    return this.debuggerConfig.isDebuggerClassFileDumpEnabled();
  }

  public int getDebuggerPollInterval() {
    return this.debuggerConfig.getDebuggerPollInterval();
  }

  public int getDebuggerDiagnosticsInterval() {
    return this.debuggerConfig.getDebuggerDiagnosticsInterval();
  }

  public boolean isDebuggerMetricsEnabled() {
    return this.debuggerConfig.isDebuggerMetricsEnabled();
  }

  public int getDebuggerUploadBatchSize() {
    return this.debuggerConfig.getDebuggerUploadBatchSize();
  }

  public long getDebuggerMaxPayloadSize() {
    return this.debuggerConfig.getDebuggerMaxPayloadSize();
  }

  public boolean isDebuggerVerifyByteCode() {
    return this.debuggerConfig.isDebuggerVerifyByteCode();
  }

  public boolean isDebuggerInstrumentTheWorld() {
    return this.debuggerConfig.isDebuggerInstrumentTheWorld();
  }

  public String getDebuggerExcludeFile() {
    return this.debuggerConfig.getDebuggerExcludeFile();
  }

  public String getDebuggerProbeFileLocation() {
    return this.debuggerConfig.getDebuggerProbeFileLocation();
  }

  public String getFinalDebuggerSnapshotUrl() {
    return this.debuggerConfig.getFinalDebuggerSnapshotUrl();
  }

  public boolean isAwsPropagationEnabled() {
    return this.traceInstrumentationConfig.isAwsPropagationEnabled();
  }

  public boolean isSqsPropagationEnabled() {
    return this.traceInstrumentationConfig.isSqsPropagationEnabled();
  }

  public boolean isKafkaClientPropagationEnabled() {
    return this.traceInstrumentationConfig.isKafkaClientPropagationEnabled();
  }

  public boolean isKafkaClientPropagationDisabledForTopic(String topic) {
    return this.traceInstrumentationConfig.isKafkaClientPropagationDisabledForTopic(topic);
  }

  public boolean isJmsPropagationEnabled() {
    return this.traceInstrumentationConfig.isJmsPropagationEnabled();
  }

  public boolean isJmsPropagationDisabledForDestination(final String queueOrTopic) {
    return this.traceInstrumentationConfig.isJmsPropagationDisabledForDestination(queueOrTopic);
  }

  public boolean isKafkaClientBase64DecodingEnabled() {
    return this.traceInstrumentationConfig.isKafkaClientBase64DecodingEnabled();
  }

  public boolean isRabbitPropagationEnabled() {
    return this.traceInstrumentationConfig.isRabbitPropagationEnabled();
  }

  public boolean isRabbitPropagationDisabledForDestination(final String queueOrExchange) {
    return this.traceInstrumentationConfig.isRabbitPropagationDisabledForDestination(
        queueOrExchange);
  }

  public boolean isMessageBrokerSplitByDestination() {
    return this.traceInstrumentationConfig.isMessageBrokerSplitByDestination();
  }

  public boolean isHystrixTagsEnabled() {
    return this.traceInstrumentationConfig.isHystrixTagsEnabled();
  }

  public boolean isHystrixMeasuredEnabled() {
    return this.traceInstrumentationConfig.isHystrixMeasuredEnabled();
  }

  public boolean isIgniteCacheIncludeKeys() {
    return this.traceInstrumentationConfig.isIgniteCacheIncludeKeys();
  }

  public String getObfuscationQueryRegexp() {
    return this.traceInstrumentationConfig.getObfuscationQueryRegexp();
  }

  public boolean getPlayReportHttpStatus() {
    return this.traceInstrumentationConfig.getPlayReportHttpStatus();
  }

  public boolean isServletPrincipalEnabled() {
    return this.traceInstrumentationConfig.isServletPrincipalEnabled();
  }

  public int getxDatadogTagsMaxLength() {
    return this.tracerConfig.getxDatadogTagsMaxLength();
  }

  public boolean isServletAsyncTimeoutError() {
    return this.traceInstrumentationConfig.isServletAsyncTimeoutError();
  }

  public boolean isTraceAgentV05Enabled() {
    return this.tracerConfig.isTraceAgentV05Enabled();
  }

  public boolean isDebugEnabled() {
    return this.generalConfig.isDebugEnabled();
  }

  public boolean isCwsEnabled() {
    return this.cwsConfig.isCwsEnabled();
  }

  public int getCwsTlsRefresh() {
    return this.cwsConfig.getCwsTlsRefresh();
  }

  public boolean isAzureAppServices() {
    return this.generalConfig.isAzureAppServices();
  }

  public boolean isDataStreamsEnabled() {
    return this.generalConfig.isDataStreamsEnabled();
  }

  public String getTraceAgentPath() {
    return this.tracerConfig.getTraceAgentPath();
  }

  public List<String> getTraceAgentArgs() {
    return this.tracerConfig.getTraceAgentArgs();
  }

  public String getDogStatsDPath() {
    return this.generalConfig.getDogStatsDPath();
  }

  public List<String> getDogStatsDArgs() {
    return this.generalConfig.getDogStatsDArgs();
  }

  public String getConfigFileStatus() {
    return configFileStatus;
  }

  public IdGenerationStrategy getIdGenerationStrategy() {
    return this.tracerConfig.getIdGenerationStrategy();
  }

  public boolean isInternalExitOnFailure() {
    return this.generalConfig.isInternalExitOnFailure();
  }

  public boolean isResolverOutlinePoolEnabled() {
    return this.traceInstrumentationConfig.isResolverOutlinePoolEnabled();
  }

  public int getResolverOutlinePoolSize() {
    return this.traceInstrumentationConfig.getResolverOutlinePoolSize();
  }

  public int getResolverTypePoolSize() {
    return this.traceInstrumentationConfig.getResolverTypePoolSize();
  }

  public boolean isResolverUseLoadClassEnabled() {
    return this.traceInstrumentationConfig.isResolverUseLoadClassEnabled();
  }

  public String getJdbcPreparedStatementClassName() {
    return this.traceInstrumentationConfig.getJdbcPreparedStatementClassName();
  }

  public String getJdbcConnectionClassName() {
    return this.traceInstrumentationConfig.getJdbcConnectionClassName();
  }

  public Set<String> getGrpcIgnoredInboundMethods() {
    return this.traceInstrumentationConfig.getGrpcIgnoredInboundMethods();
  }

  public Set<String> getGrpcIgnoredOutboundMethods() {
    return this.traceInstrumentationConfig.getGrpcIgnoredOutboundMethods();
  }

  public boolean isGrpcServerTrimPackageResource() {
    return this.traceInstrumentationConfig.isGrpcServerTrimPackageResource();
  }

  public BitSet getGrpcServerErrorStatuses() {
    return this.traceInstrumentationConfig.getGrpcServerErrorStatuses();
  }

  public BitSet getGrpcClientErrorStatuses() {
    return this.traceInstrumentationConfig.getGrpcClientErrorStatuses();
  }

  public Map<String, Object> getLocalRootSpanTags() {
    return this.generalConfig.getLocalRootSpanTags();
  }

  public WellKnownTags getWellKnownTags() {
    return this.generalConfig.getWellKnownTags();
  }

  public String getPrimaryTag() {
    return this.generalConfig.getPrimaryTag();
  }

  public Map<String, String> getGlobalTags() {
    return this.generalConfig.getGlobalTags();
  }

  public Set<String> getMetricsIgnoredResources() {
    return tryMakeImmutableSet(this.configProvider.getList(TRACER_METRICS_IGNORED_RESOURCES));
  }

  public String getEnv() {
    return this.generalConfig.getEnv();
  }

  public String getVersion() {
    return this.generalConfig.getVersion();
  }

  public Map<String, String> getMergedSpanTags() {
    return this.generalConfig.getMergedSpanTags();
  }

  public Map<String, String> getMergedJmxTags() {
    return this.jmxFetchConfig.getMergedJmxTags();
  }

  public Map<String, String> getMergedProfilingTags() {
    return this.profilingConfig.getMergedProfilingTags();
  }

  public Map<String, String> getMergedCrashTrackingTags() {
    return this.crashTrackingConfig.getMergedCrashTrackingTags();
  }

  /** Returns the sample rate for the specified instrumentation. */
  public float getInstrumentationAnalyticsSampleRate(final String... aliases) {
    return this.tracerConfig.getInstrumentationAnalyticsSampleRate(aliases);
  }

  public String getFinalProfilingUrl() {
    return this.profilingConfig.getFinalProfilingUrl();
  }

  public String getFinalCrashTrackingTelemetryUrl() {
    return this.crashTrackingConfig.getFinalCrashTrackingTelemetryUrl();
  }

  public boolean isIntegrationEnabled(
      final Iterable<String> integrationNames, final boolean defaultEnabled) {
    return this.traceInstrumentationConfig.isIntegrationEnabled(integrationNames, defaultEnabled);
  }

  public boolean isIntegrationShortcutMatchingEnabled(
      final Iterable<String> integrationNames, final boolean defaultEnabled) {
    return this.traceInstrumentationConfig.isIntegrationShortcutMatchingEnabled(
        integrationNames, defaultEnabled);
  }

  public boolean isJmxFetchIntegrationEnabled(
      final Iterable<String> integrationNames, final boolean defaultEnabled) {
    return this.traceInstrumentationConfig.isJmxFetchIntegrationEnabled(
        integrationNames, defaultEnabled);
  }

  public boolean isRuleEnabled(final String name) {
    return this.traceInstrumentationConfig.isRuleEnabled(name);
  }

  public boolean isRuleEnabled(final String name, boolean defaultEnabled) {
    return this.traceInstrumentationConfig.isRuleEnabled(name, defaultEnabled);
  }

  /**
   * @param integrationNames
   * @param defaultEnabled
   * @return
   * @deprecated This method should only be used internally. Use the instance getter instead {@link
   *     #isJmxFetchIntegrationEnabled(Iterable, boolean)}.
   */
  public static boolean jmxFetchIntegrationEnabled(
      final SortedSet<String> integrationNames, final boolean defaultEnabled) {
    return Config.get().isJmxFetchIntegrationEnabled(integrationNames, defaultEnabled);
  }

  public boolean isEndToEndDurationEnabled(
      final boolean defaultEnabled, final String... integrationNames) {
    return this.traceInstrumentationConfig.isEndToEndDurationEnabled(
        defaultEnabled, integrationNames);
  }

  public boolean isLegacyTracingEnabled(
      final boolean defaultEnabled, final String... integrationNames) {
    return this.traceInstrumentationConfig.isLegacyTracingEnabled(defaultEnabled, integrationNames);
  }

  public boolean isTraceAnalyticsIntegrationEnabled(
      final SortedSet<String> integrationNames, final boolean defaultEnabled) {
    return this.traceInstrumentationConfig.isTraceAnalyticsIntegrationEnabled(
        integrationNames, defaultEnabled);
  }

  public boolean isTraceAnalyticsIntegrationEnabled(
      final boolean defaultEnabled, final String... integrationNames) {
    return this.traceInstrumentationConfig.isTraceAnalyticsIntegrationEnabled(
        defaultEnabled, integrationNames);
  }

  public boolean isSamplingMechanismValidationDisabled() {
    return this.tracerConfig.isSamplingMechanismValidationDisabled();
  }

  public <T extends Enum<T>> T getEnumValue(
      final String name, final Class<T> type, final T defaultValue) {
    return this.configProvider.getEnum(name, type, defaultValue);
  }

  /**
   * @param integrationNames
   * @param defaultEnabled
   * @return
   * @deprecated This method should only be used internally. Use the instance getter instead {@link
   *     #isTraceAnalyticsIntegrationEnabled(SortedSet, boolean)}.
   */
  public static boolean traceAnalyticsIntegrationEnabled(
      final SortedSet<String> integrationNames, final boolean defaultEnabled) {
    return Config.get().isTraceAnalyticsIntegrationEnabled(integrationNames, defaultEnabled);
  }

  // This has to be placed after all other static fields to give them a chance to initialize
  @SuppressFBWarnings("SI_INSTANCE_BEFORE_FINALS_ASSIGNED")
  private static final Config INSTANCE = new Config();

  public static Config get() {
    return INSTANCE;
  }

  /**
   * This method is deprecated since the method of configuration will be changed in the future. The
   * properties instance should instead be passed directly into the DDTracer builder:
   *
   * <pre>
   *   DDTracer.builder().withProperties(new Properties()).build()
   * </pre>
   *
   * @deprecated
   */
  @Deprecated
  public static Config get(final Properties properties) {
    if (properties == null || properties.isEmpty()) {
      return INSTANCE;
    } else {
      return new Config(ConfigProvider.withPropertiesOverride(properties));
    }
  }

  @Override
  public String toString() {
    return "Config{"
        + "generalConfig="
        + this.generalConfig
        + ", tracerConfig="
        + this.tracerConfig
        + ", iastConfig="
        + this.iastConfig
        + ", jmxFetchConfig="
        + this.jmxFetchConfig
        + ", traceInstrumentationConfig="
        + this.traceInstrumentationConfig
        + ", profilingConfig="
        + this.profilingConfig
        + ", crashTrackingConfig="
        + this.crashTrackingConfig
        + ", appSecConfig="
        + this.appSecConfig
        + ", ciVisibilityConfig="
        + this.ciVisibilityConfig
        + ", remoteConfig="
        + this.remoteConfig
        + ", debuggerConfig="
        + this.debuggerConfig
        + ", cwsConfig="
        + this.cwsConfig
        + ", configFileStatus='"
        + this.configFileStatus
        + '\''
        + ", configProvider="
        + this.configProvider
        + '}';
  }
}
