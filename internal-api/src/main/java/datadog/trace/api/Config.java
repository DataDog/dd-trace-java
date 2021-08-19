package datadog.trace.api;

import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_HOST;
import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_TIMEOUT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_WRITER_TYPE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_ANALYTICS_SAMPLE_RATE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_APPSEC_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_AWS_SQS_AS_SEPARATE_SERVICE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DOGSTATSD_START_DELAY;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HEALTH_METRICS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_CLIENT_ERROR_STATUSES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_CLIENT_SPLIT_BY_DOMAIN;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_CLIENT_TAG_QUERY_STRING;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_SERVER_ERROR_STATUSES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_SERVER_ROUTE_BASED_NAMING;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_SERVER_TAG_QUERY_STRING;
import static datadog.trace.api.ConfigDefaults.DEFAULT_INTEGRATIONS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_JMS_PROPAGATION_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_JMX_FETCH_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_KAFKA_CLIENT_PROPAGATION_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_LOGS_INJECTION_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PARTIAL_FLUSH_MIN_SPANS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PERF_METRICS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PRIORITY_SAMPLING_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PRIORITY_SAMPLING_FORCE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROFILING_AGENTLESS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROFILING_ALLOCATION_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROFILING_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROFILING_EXCEPTION_SAMPLE_LIMIT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROFILING_HEAP_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROFILING_LEGACY_TRACING_INTEGRATION;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROFILING_PROXY_PORT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROFILING_START_DELAY;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROFILING_START_FORCE_FIRST;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROFILING_UPLOAD_COMPRESSION;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROFILING_UPLOAD_PERIOD;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROFILING_UPLOAD_TIMEOUT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROPAGATION_STYLE_EXTRACT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROPAGATION_STYLE_INJECT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_RABBIT_PROPAGATION_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_RUNTIME_CONTEXT_FIELD_INJECTION;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SCOPE_DEPTH_LIMIT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SERIALVERSIONUID_FIELD_INJECTION;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SERVICE_NAME;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SITE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_AGENT_PORT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_AGENT_V05_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_ANALYTICS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_ANNOTATIONS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_EXECUTORS_ALL;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_METHODS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_RATE_LIMIT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_REPORT_HOSTNAME;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_RESOLVER_ENABLED;
import static datadog.trace.api.DDTags.HOST_TAG;
import static datadog.trace.api.DDTags.INTERNAL_HOST_NAME;
import static datadog.trace.api.DDTags.LANGUAGE_TAG_KEY;
import static datadog.trace.api.DDTags.LANGUAGE_TAG_VALUE;
import static datadog.trace.api.DDTags.RUNTIME_ID_TAG;
import static datadog.trace.api.DDTags.SERVICE;
import static datadog.trace.api.DDTags.SERVICE_TAG;
import static datadog.trace.api.IdGenerationStrategy.RANDOM;
import static datadog.trace.api.Platform.isJavaVersionAtLeast;
import static datadog.trace.api.config.AppSecConfig.APPSEC_ENABLED;
import static datadog.trace.api.config.GeneralConfig.API_KEY;
import static datadog.trace.api.config.GeneralConfig.API_KEY_FILE;
import static datadog.trace.api.config.GeneralConfig.CONFIGURATION_FILE;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_HOST;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_PORT;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_START_DELAY;
import static datadog.trace.api.config.GeneralConfig.ENV;
import static datadog.trace.api.config.GeneralConfig.GLOBAL_TAGS;
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_STATSD_HOST;
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_STATSD_PORT;
import static datadog.trace.api.config.GeneralConfig.INTERNAL_EXIT_ON_FAILURE;
import static datadog.trace.api.config.GeneralConfig.PERF_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.RUNTIME_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME;
import static datadog.trace.api.config.GeneralConfig.SITE;
import static datadog.trace.api.config.GeneralConfig.TAGS;
import static datadog.trace.api.config.GeneralConfig.TRACER_METRICS_BUFFERING_ENABLED;
import static datadog.trace.api.config.GeneralConfig.TRACER_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.TRACER_METRICS_IGNORED_RESOURCES;
import static datadog.trace.api.config.GeneralConfig.TRACER_METRICS_MAX_AGGREGATES;
import static datadog.trace.api.config.GeneralConfig.TRACER_METRICS_MAX_PENDING;
import static datadog.trace.api.config.GeneralConfig.VERSION;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_CHECK_PERIOD;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_CONFIG;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_CONFIG_DIR;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_ENABLED;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_INITIAL_REFRESH_BEANS_PERIOD;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_METRICS_CONFIGS;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_REFRESH_BEANS_PERIOD;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_START_DELAY;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_STATSD_HOST;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_STATSD_PORT;
import static datadog.trace.api.config.JmxFetchConfig.JMX_TAGS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_AGENTLESS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ALLOCATION_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_API_KEY_FILE_OLD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_API_KEY_FILE_VERY_OLD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_API_KEY_OLD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_API_KEY_VERY_OLD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_SAMPLE_LIMIT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCLUDE_AGENT_THREADS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_HEAP_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_HOTSPOTS_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_LEGACY_TRACING_INTEGRATION;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_PROXY_HOST;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_PROXY_PASSWORD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_PROXY_PORT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_PROXY_USERNAME;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_START_DELAY;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_START_FORCE_FIRST;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_TAGS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_COMPRESSION;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_PERIOD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_UPLOAD_TIMEOUT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_URL;
import static datadog.trace.api.config.TraceInstrumentationConfig.AWS_SQS_AS_SEPARATE_SERVICE;
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE;
import static datadog.trace.api.config.TraceInstrumentationConfig.GRPC_IGNORED_OUTBOUND_METHODS;
import static datadog.trace.api.config.TraceInstrumentationConfig.GRPC_SERVER_TRIM_PACKAGE_RESOURCE;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_CLIENT_TAG_QUERY_STRING;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_RAW_QUERY_STRING;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_RAW_RESOURCE;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_ROUTE_BASED_NAMING;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_SERVER_TAG_QUERY_STRING;
import static datadog.trace.api.config.TraceInstrumentationConfig.HYSTRIX_MEASURED_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.HYSTRIX_TAGS_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.IGNITE_CACHE_INCLUDE_KEYS;
import static datadog.trace.api.config.TraceInstrumentationConfig.INTEGRATIONS_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.JDBC_CONNECTION_CLASS_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.JDBC_PREPARED_STATEMENT_CLASS_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_QUEUES;
import static datadog.trace.api.config.TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_TOPICS;
import static datadog.trace.api.config.TraceInstrumentationConfig.JMS_PROPAGATION_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.KAFKA_CLIENT_BASE64_DECODING_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.KAFKA_CLIENT_PROPAGATION_DISABLED_TOPICS;
import static datadog.trace.api.config.TraceInstrumentationConfig.KAFKA_CLIENT_PROPAGATION_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.LOGS_INJECTION_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.LOGS_MDC_TAGS_INJECTION_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.OSGI_SEARCH_DEPTH;
import static datadog.trace.api.config.TraceInstrumentationConfig.PLAY_REPORT_HTTP_STATUS;
import static datadog.trace.api.config.TraceInstrumentationConfig.RABBIT_PROPAGATION_DISABLED_EXCHANGES;
import static datadog.trace.api.config.TraceInstrumentationConfig.RABBIT_PROPAGATION_DISABLED_QUEUES;
import static datadog.trace.api.config.TraceInstrumentationConfig.RABBIT_PROPAGATION_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.RESOLVER_USE_LOADCLASS;
import static datadog.trace.api.config.TraceInstrumentationConfig.RUNTIME_CONTEXT_FIELD_INJECTION;
import static datadog.trace.api.config.TraceInstrumentationConfig.SERIALVERSIONUID_FIELD_INJECTION;
import static datadog.trace.api.config.TraceInstrumentationConfig.SERVLET_ASYNC_TIMEOUT_ERROR;
import static datadog.trace.api.config.TraceInstrumentationConfig.SERVLET_PRINCIPAL_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.SERVLET_ROOT_CONTEXT_SERVICE_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.TEMP_JARS_CLEAN_ON_BOOT;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_ANNOTATIONS;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_CLASSES_EXCLUDE;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_EXECUTORS;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_EXECUTORS_ALL;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_METHODS;
import static datadog.trace.api.config.TracerConfig.AGENT_HOST;
import static datadog.trace.api.config.TracerConfig.AGENT_PORT_LEGACY;
import static datadog.trace.api.config.TracerConfig.AGENT_TIMEOUT;
import static datadog.trace.api.config.TracerConfig.AGENT_UNIX_DOMAIN_SOCKET;
import static datadog.trace.api.config.TracerConfig.ENABLE_TRACE_AGENT_V05;
import static datadog.trace.api.config.TracerConfig.HEADER_TAGS;
import static datadog.trace.api.config.TracerConfig.HTTP_CLIENT_ERROR_STATUSES;
import static datadog.trace.api.config.TracerConfig.HTTP_SERVER_ERROR_STATUSES;
import static datadog.trace.api.config.TracerConfig.ID_GENERATION_STRATEGY;
import static datadog.trace.api.config.TracerConfig.PARTIAL_FLUSH_MIN_SPANS;
import static datadog.trace.api.config.TracerConfig.PRIORITY_SAMPLING;
import static datadog.trace.api.config.TracerConfig.PRIORITY_SAMPLING_FORCE;
import static datadog.trace.api.config.TracerConfig.PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED;
import static datadog.trace.api.config.TracerConfig.PROPAGATION_STYLE_EXTRACT;
import static datadog.trace.api.config.TracerConfig.PROPAGATION_STYLE_INJECT;
import static datadog.trace.api.config.TracerConfig.PROXY_NO_PROXY;
import static datadog.trace.api.config.TracerConfig.SCOPE_DEPTH_LIMIT;
import static datadog.trace.api.config.TracerConfig.SCOPE_INHERIT_ASYNC_PROPAGATION;
import static datadog.trace.api.config.TracerConfig.SCOPE_STRICT_MODE;
import static datadog.trace.api.config.TracerConfig.SERVICE_MAPPING;
import static datadog.trace.api.config.TracerConfig.SPAN_TAGS;
import static datadog.trace.api.config.TracerConfig.SPLIT_BY_TAGS;
import static datadog.trace.api.config.TracerConfig.TRACE_AGENT_PORT;
import static datadog.trace.api.config.TracerConfig.TRACE_AGENT_URL;
import static datadog.trace.api.config.TracerConfig.TRACE_ANALYTICS_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING;
import static datadog.trace.api.config.TracerConfig.TRACE_RATE_LIMIT;
import static datadog.trace.api.config.TracerConfig.TRACE_REPORT_HOSTNAME;
import static datadog.trace.api.config.TracerConfig.TRACE_RESOLVER_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_OPERATION_RULES;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_SERVICE_RULES;
import static datadog.trace.api.config.TracerConfig.TRACE_STRICT_WRITES_ENABLED;
import static datadog.trace.api.config.TracerConfig.WRITER_TYPE;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableList;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableSet;
import static datadog.trace.util.Strings.propertyNameToEnvironmentVariableName;
import static datadog.trace.util.Strings.toEnvVar;

import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.bootstrap.config.provider.CapturedEnvironmentConfigSource;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.config.provider.SystemPropertiesConfigSource;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Config reads values with the following priority: 1) system properties, 2) environment variables,
 * 3) optional configuration file, 4) platform dependant properties. It also includes default values
 * to ensure a valid config.
 *
 * <p>
 *
 * <p>System properties are {@link Config#PREFIX}'ed. Environment variables are the same as the
 * system property, but uppercased and '.' is replaced with '_'.
 */
@Deprecated
public class Config {

  private static final Logger log = LoggerFactory.getLogger(Config.class);

  private final long startTimeMillis = System.currentTimeMillis();

  /**
   * this is a random UUID that gets generated on JVM start up and is attached to every root span
   * and every JMX metric that is sent out.
   */
  private final String runtimeId;

  /**
   * Note: this has effect only on profiling site. Traces are sent to Datadog agent and are not
   * affected by this setting.
   */
  private final String apiKey;
  /**
   * Note: this has effect only on profiling site. Traces are sent to Datadog agent and are not
   * affected by this setting.
   */
  private final String site;

  private final String serviceName;
  private final boolean serviceNameSetByUser;
  private final String rootContextServiceName;
  private final boolean traceEnabled;
  private final boolean integrationsEnabled;
  private final String writerType;
  private final boolean agentConfiguredUsingDefault;
  private final String agentUrl;
  private final String agentHost;
  private final int agentPort;
  private final String agentUnixDomainSocket;
  private final int agentTimeout;
  private final Set<String> noProxyHosts;
  private final boolean prioritySamplingEnabled;
  private final String prioritySamplingForce;
  private final boolean traceResolverEnabled;
  private final Map<String, String> serviceMapping;
  private final Map<String, String> tags;
  private final Map<String, String> spanTags;
  private final Map<String, String> jmxTags;
  private final List<String> excludedClasses;
  private final Map<String, String> headerTags;
  private final BitSet httpServerErrorStatuses;
  private final BitSet httpClientErrorStatuses;
  private final boolean httpServerTagQueryString;
  private final boolean httpServerRawQueryString;
  private final boolean httpServerRawResource;
  private final boolean httpServerRouteBasedNaming;
  private final Map<String, String> httpServerPathResourceNameMapping;
  private final boolean httpClientTagQueryString;
  private final boolean httpClientSplitByDomain;
  private final boolean dbClientSplitByInstance;
  private final Set<String> splitByTags;
  private final int scopeDepthLimit;
  private final boolean scopeStrictMode;
  private final boolean scopeInheritAsyncPropagation;
  private final int partialFlushMinSpans;
  private final boolean traceStrictWritesEnabled;
  private final boolean runtimeContextFieldInjection;
  private final boolean serialVersionUIDFieldInjection;
  private final boolean logExtractHeaderNames;
  private final Set<PropagationStyle> propagationStylesToExtract;
  private final Set<PropagationStyle> propagationStylesToInject;

  private final boolean jmxFetchEnabled;
  private final int dogStatsDStartDelay;
  private final String jmxFetchConfigDir;
  private final List<String> jmxFetchConfigs;
  @Deprecated private final List<String> jmxFetchMetricsConfigs;
  private final Integer jmxFetchCheckPeriod;
  private final Integer jmxFetchInitialRefreshBeansPeriod;
  private final Integer jmxFetchRefreshBeansPeriod;
  private final String jmxFetchStatsdHost;
  private final Integer jmxFetchStatsdPort;

  // These values are default-ed to those of jmx fetch values as needed
  private final boolean healthMetricsEnabled;
  private final String healthMetricsStatsdHost;
  private final Integer healthMetricsStatsdPort;
  private final boolean perfMetricsEnabled;

  private final boolean tracerMetricsEnabled;
  private final boolean tracerMetricsBufferingEnabled;
  private final int tracerMetricsMaxAggregates;
  private final int tracerMetricsMaxPending;

  private final boolean logsInjectionEnabled;
  private final boolean logsMDCTagsInjectionEnabled;
  private final boolean reportHostName;

  private final String traceAnnotations;

  private final String traceMethods;

  private final boolean traceExecutorsAll;
  private final List<String> traceExecutors;

  private final boolean traceAnalyticsEnabled;

  private final Map<String, String> traceSamplingServiceRules;
  private final Map<String, String> traceSamplingOperationRules;
  private final Double traceSampleRate;
  private final int traceRateLimit;

  private final boolean profilingEnabled;
  private final boolean profilingAllocationEnabled;
  private final boolean profilingHeapEnabled;
  private final boolean profilingAgentless;
  private final boolean profilingLegacyTracingIntegrationEnabled;
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

  private final boolean appSecEnabled;

  private final boolean kafkaClientPropagationEnabled;
  private final Set<String> kafkaClientPropagationDisabledTopics;
  private final boolean kafkaClientBase64DecodingEnabled;

  private final boolean jmsPropagationEnabled;
  private final Set<String> jmsPropagationDisabledTopics;
  private final Set<String> jmsPropagationDisabledQueues;

  private final boolean awsSqsAsSeparateService;

  private final boolean rabbitPropagationEnabled;
  private final Set<String> rabbitPropagationDisabledQueues;
  private final Set<String> rabbitPropagationDisabledExchanges;

  private final boolean hystrixTagsEnabled;
  private final boolean hystrixMeasuredEnabled;

  private final boolean igniteCacheIncludeKeys;

  private final int osgiSearchDepth;

  // TODO: remove at a future point.
  private final boolean playReportHttpStatus;

  private final boolean servletPrincipalEnabled;
  private final boolean servletAsyncTimeoutError;

  private final boolean tempJarsCleanOnBoot;

  private final boolean traceAgentV05Enabled;

  private final boolean debugEnabled;
  private final String configFile;

  private final IdGenerationStrategy idGenerationStrategy;

  private final boolean internalExitOnFailure;

  private final boolean resolverUseLoadClassEnabled;

  private final String jdbcPreparedStatementClassName;
  private final String jdbcConnectionClassName;

  private final Set<String> grpcIgnoredOutboundMethods;
  private final boolean grpcServerTrimPackageResource;

  private String env;
  private String version;

  private final ConfigProvider configProvider;

  // Read order: System Properties -> Env Variables, [-> properties file], [-> default value]
  private Config() {
    this(
        INSTANCE != null ? INSTANCE.runtimeId : UUID.randomUUID().toString(),
        ConfigProvider.createDefault());
  }

  private Config(final String runtimeId, final ConfigProvider configProvider) {
    this.configProvider = configProvider;
    configFile = findConfigurationFile();
    this.runtimeId = runtimeId;

    // Note: We do not want APiKey to be loaded from property for security reasons
    // Note: we do not use defined default here
    // FIXME: We should use better authentication mechanism
    final String apiKeyFile = configProvider.getString(API_KEY_FILE);
    String tmpApiKey =
        configProvider.getStringExcludingSource(API_KEY, null, SystemPropertiesConfigSource.class);
    if (apiKeyFile != null) {
      try {
        tmpApiKey =
            new String(Files.readAllBytes(Paths.get(apiKeyFile)), StandardCharsets.UTF_8).trim();
      } catch (final IOException e) {
        log.error("Cannot read API key from file {}, skipping", apiKeyFile, e);
      }
    }
    site = configProvider.getString(SITE, DEFAULT_SITE);
    String userProvidedServiceName =
        configProvider.getStringExcludingSource(
            SERVICE, null, CapturedEnvironmentConfigSource.class, SERVICE_NAME);

    if (userProvidedServiceName == null) {
      serviceNameSetByUser = false;
      serviceName = configProvider.getString(SERVICE, DEFAULT_SERVICE_NAME, SERVICE_NAME);
    } else {
      serviceNameSetByUser = true;
      serviceName = userProvidedServiceName;
    }

    rootContextServiceName =
        configProvider.getString(
            SERVLET_ROOT_CONTEXT_SERVICE_NAME, DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME);

    traceEnabled = configProvider.getBoolean(TRACE_ENABLED, DEFAULT_TRACE_ENABLED);
    integrationsEnabled =
        configProvider.getBoolean(INTEGRATIONS_ENABLED, DEFAULT_INTEGRATIONS_ENABLED);
    writerType = configProvider.getString(WRITER_TYPE, DEFAULT_AGENT_WRITER_TYPE);

    idGenerationStrategy =
        configProvider.getEnum(ID_GENERATION_STRATEGY, IdGenerationStrategy.class, RANDOM);
    if (idGenerationStrategy != RANDOM) {
      log.warn(
          "*** you are using an unsupported id generation strategy {} - this can impact correctness of traces",
          idGenerationStrategy);
    }

    String agentHostFromEnvironment = null;
    int agentPortFromEnvironment = -1;
    String unixSocketFromEnvironment = null;
    boolean rebuildAgentUrl = false;

    final String agentUrlFromEnvironment = configProvider.getString(TRACE_AGENT_URL);
    if (agentUrlFromEnvironment != null) {
      try {
        final URI parsedAgentUrl = new URI(agentUrlFromEnvironment);
        agentHostFromEnvironment = parsedAgentUrl.getHost();
        agentPortFromEnvironment = parsedAgentUrl.getPort();
        if ("unix".equals(parsedAgentUrl.getScheme())) {
          unixSocketFromEnvironment = parsedAgentUrl.getPath();
        }
      } catch (URISyntaxException e) {
        log.warn("{} not configured correctly: {}. Ignoring", TRACE_AGENT_URL, e.getMessage());
      }
    }

    if (agentHostFromEnvironment == null) {
      agentHostFromEnvironment = configProvider.getString(AGENT_HOST);
      rebuildAgentUrl = true;
    }

    if (agentPortFromEnvironment < 0) {
      agentPortFromEnvironment = configProvider.getInteger(TRACE_AGENT_PORT, -1, AGENT_PORT_LEGACY);
      rebuildAgentUrl = true;
    }

    if (agentHostFromEnvironment == null) {
      agentHost = DEFAULT_AGENT_HOST;
    } else {
      agentHost = agentHostFromEnvironment;
    }

    if (agentPortFromEnvironment < 0) {
      agentPort = DEFAULT_TRACE_AGENT_PORT;
    } else {
      agentPort = agentPortFromEnvironment;
    }

    if (rebuildAgentUrl) {
      agentUrl = "http://" + agentHost + ":" + agentPort;
    } else {
      agentUrl = agentUrlFromEnvironment;
    }

    if (unixSocketFromEnvironment == null) {
      unixSocketFromEnvironment = configProvider.getString(AGENT_UNIX_DOMAIN_SOCKET);
      String unixPrefix = "unix://";
      // handle situation where someone passes us a unix:// URL instead of a socket path
      if (unixSocketFromEnvironment != null && unixSocketFromEnvironment.startsWith(unixPrefix)) {
        unixSocketFromEnvironment = unixSocketFromEnvironment.substring(unixPrefix.length());
      }
    }

    agentUnixDomainSocket = unixSocketFromEnvironment;

    agentConfiguredUsingDefault =
        agentHostFromEnvironment == null
            && agentPortFromEnvironment < 0
            && unixSocketFromEnvironment == null;

    agentTimeout = configProvider.getInteger(AGENT_TIMEOUT, DEFAULT_AGENT_TIMEOUT);

    // DD_PROXY_NO_PROXY is specified as a space-separated list of hosts
    noProxyHosts = tryMakeImmutableSet(configProvider.getSpacedList(PROXY_NO_PROXY));

    prioritySamplingEnabled =
        configProvider.getBoolean(PRIORITY_SAMPLING, DEFAULT_PRIORITY_SAMPLING_ENABLED);
    prioritySamplingForce =
        configProvider.getString(PRIORITY_SAMPLING_FORCE, DEFAULT_PRIORITY_SAMPLING_FORCE);

    traceResolverEnabled =
        configProvider.getBoolean(TRACE_RESOLVER_ENABLED, DEFAULT_TRACE_RESOLVER_ENABLED);
    serviceMapping = configProvider.getMergedMap(SERVICE_MAPPING);

    {
      final Map<String, String> tags = new HashMap<>(configProvider.getMergedMap(GLOBAL_TAGS));
      tags.putAll(configProvider.getMergedMap(TAGS));
      this.tags = getMapWithPropertiesDefinedByEnvironment(tags, ENV, VERSION);
    }

    spanTags = configProvider.getMergedMap(SPAN_TAGS);
    jmxTags = configProvider.getMergedMap(JMX_TAGS);

    excludedClasses = tryMakeImmutableList(configProvider.getList(TRACE_CLASSES_EXCLUDE));
    headerTags = configProvider.getMergedMap(HEADER_TAGS);

    httpServerPathResourceNameMapping =
        configProvider.getOrderedMap(TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING);

    httpServerErrorStatuses =
        configProvider.getIntegerRange(
            HTTP_SERVER_ERROR_STATUSES, DEFAULT_HTTP_SERVER_ERROR_STATUSES);

    httpClientErrorStatuses =
        configProvider.getIntegerRange(
            HTTP_CLIENT_ERROR_STATUSES, DEFAULT_HTTP_CLIENT_ERROR_STATUSES);

    httpServerTagQueryString =
        configProvider.getBoolean(
            HTTP_SERVER_TAG_QUERY_STRING, DEFAULT_HTTP_SERVER_TAG_QUERY_STRING);

    httpServerRawQueryString = configProvider.getBoolean(HTTP_SERVER_RAW_QUERY_STRING, true);

    httpServerRawResource = configProvider.getBoolean(HTTP_SERVER_RAW_RESOURCE, false);

    httpServerRouteBasedNaming =
        configProvider.getBoolean(
            HTTP_SERVER_ROUTE_BASED_NAMING, DEFAULT_HTTP_SERVER_ROUTE_BASED_NAMING);

    httpClientTagQueryString =
        configProvider.getBoolean(
            HTTP_CLIENT_TAG_QUERY_STRING, DEFAULT_HTTP_CLIENT_TAG_QUERY_STRING);

    httpClientSplitByDomain =
        configProvider.getBoolean(
            HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, DEFAULT_HTTP_CLIENT_SPLIT_BY_DOMAIN);

    dbClientSplitByInstance =
        configProvider.getBoolean(
            DB_CLIENT_HOST_SPLIT_BY_INSTANCE, DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE);

    splitByTags = tryMakeImmutableSet(configProvider.getList(SPLIT_BY_TAGS));

    scopeDepthLimit = configProvider.getInteger(SCOPE_DEPTH_LIMIT, DEFAULT_SCOPE_DEPTH_LIMIT);

    scopeStrictMode = configProvider.getBoolean(SCOPE_STRICT_MODE, false);

    scopeInheritAsyncPropagation = configProvider.getBoolean(SCOPE_INHERIT_ASYNC_PROPAGATION, true);

    partialFlushMinSpans =
        configProvider.getInteger(PARTIAL_FLUSH_MIN_SPANS, DEFAULT_PARTIAL_FLUSH_MIN_SPANS);

    traceStrictWritesEnabled = configProvider.getBoolean(TRACE_STRICT_WRITES_ENABLED, false);

    runtimeContextFieldInjection =
        configProvider.getBoolean(
            RUNTIME_CONTEXT_FIELD_INJECTION, DEFAULT_RUNTIME_CONTEXT_FIELD_INJECTION);
    serialVersionUIDFieldInjection =
        configProvider.getBoolean(
            SERIALVERSIONUID_FIELD_INJECTION, DEFAULT_SERIALVERSIONUID_FIELD_INJECTION);

    logExtractHeaderNames =
        configProvider.getBoolean(
            PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED,
            DEFAULT_PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED);

    propagationStylesToExtract =
        getPropagationStyleSetSettingFromEnvironmentOrDefault(
            PROPAGATION_STYLE_EXTRACT, DEFAULT_PROPAGATION_STYLE_EXTRACT);
    propagationStylesToInject =
        getPropagationStyleSetSettingFromEnvironmentOrDefault(
            PROPAGATION_STYLE_INJECT, DEFAULT_PROPAGATION_STYLE_INJECT);

    dogStatsDStartDelay =
        configProvider.getInteger(
            DOGSTATSD_START_DELAY, DEFAULT_DOGSTATSD_START_DELAY, JMX_FETCH_START_DELAY);

    boolean runtimeMetricsEnabled = configProvider.getBoolean(RUNTIME_METRICS_ENABLED, true);

    jmxFetchEnabled =
        runtimeMetricsEnabled
            && configProvider.getBoolean(JMX_FETCH_ENABLED, DEFAULT_JMX_FETCH_ENABLED);
    jmxFetchConfigDir = configProvider.getString(JMX_FETCH_CONFIG_DIR);
    jmxFetchConfigs = tryMakeImmutableList(configProvider.getList(JMX_FETCH_CONFIG));
    jmxFetchMetricsConfigs =
        tryMakeImmutableList(configProvider.getList(JMX_FETCH_METRICS_CONFIGS));
    jmxFetchCheckPeriod = configProvider.getInteger(JMX_FETCH_CHECK_PERIOD);
    jmxFetchInitialRefreshBeansPeriod =
        configProvider.getInteger(JMX_FETCH_INITIAL_REFRESH_BEANS_PERIOD);
    jmxFetchRefreshBeansPeriod = configProvider.getInteger(JMX_FETCH_REFRESH_BEANS_PERIOD);

    jmxFetchStatsdPort = configProvider.getInteger(JMX_FETCH_STATSD_PORT, DOGSTATSD_PORT);
    jmxFetchStatsdHost =
        configProvider.getString(
            JMX_FETCH_STATSD_HOST,
            // default to agent host if an explicit port has been set
            null != jmxFetchStatsdPort && jmxFetchStatsdPort > 0 ? agentHost : null,
            DOGSTATSD_HOST);

    // Writer.Builder createMonitor will use the values of the JMX fetch & agent to fill-in defaults
    healthMetricsEnabled =
        runtimeMetricsEnabled
            && configProvider.getBoolean(HEALTH_METRICS_ENABLED, DEFAULT_HEALTH_METRICS_ENABLED);
    healthMetricsStatsdHost = configProvider.getString(HEALTH_METRICS_STATSD_HOST);
    healthMetricsStatsdPort = configProvider.getInteger(HEALTH_METRICS_STATSD_PORT);
    perfMetricsEnabled =
        runtimeMetricsEnabled
            && isJavaVersionAtLeast(8)
            && configProvider.getBoolean(PERF_METRICS_ENABLED, DEFAULT_PERF_METRICS_ENABLED);

    tracerMetricsEnabled =
        isJavaVersionAtLeast(8) && configProvider.getBoolean(TRACER_METRICS_ENABLED, false);
    tracerMetricsBufferingEnabled =
        configProvider.getBoolean(TRACER_METRICS_BUFFERING_ENABLED, false);
    tracerMetricsMaxAggregates = configProvider.getInteger(TRACER_METRICS_MAX_AGGREGATES, 2048);
    tracerMetricsMaxPending = configProvider.getInteger(TRACER_METRICS_MAX_PENDING, 2048);

    logsInjectionEnabled =
        configProvider.getBoolean(LOGS_INJECTION_ENABLED, DEFAULT_LOGS_INJECTION_ENABLED);
    logsMDCTagsInjectionEnabled = configProvider.getBoolean(LOGS_MDC_TAGS_INJECTION_ENABLED, true);
    reportHostName =
        configProvider.getBoolean(TRACE_REPORT_HOSTNAME, DEFAULT_TRACE_REPORT_HOSTNAME);

    traceAgentV05Enabled =
        configProvider.getBoolean(ENABLE_TRACE_AGENT_V05, DEFAULT_TRACE_AGENT_V05_ENABLED);

    traceAnnotations = configProvider.getString(TRACE_ANNOTATIONS, DEFAULT_TRACE_ANNOTATIONS);

    traceMethods = configProvider.getString(TRACE_METHODS, DEFAULT_TRACE_METHODS);

    traceExecutorsAll = configProvider.getBoolean(TRACE_EXECUTORS_ALL, DEFAULT_TRACE_EXECUTORS_ALL);

    traceExecutors = tryMakeImmutableList(configProvider.getList(TRACE_EXECUTORS));

    traceAnalyticsEnabled =
        configProvider.getBoolean(TRACE_ANALYTICS_ENABLED, DEFAULT_TRACE_ANALYTICS_ENABLED);

    traceSamplingServiceRules = configProvider.getMergedMap(TRACE_SAMPLING_SERVICE_RULES);
    traceSamplingOperationRules = configProvider.getMergedMap(TRACE_SAMPLING_OPERATION_RULES);
    traceSampleRate = configProvider.getDouble(TRACE_SAMPLE_RATE);
    traceRateLimit = configProvider.getInteger(TRACE_RATE_LIMIT, DEFAULT_TRACE_RATE_LIMIT);

    profilingEnabled = configProvider.getBoolean(PROFILING_ENABLED, DEFAULT_PROFILING_ENABLED);
    profilingAllocationEnabled =
        configProvider.getBoolean(
            PROFILING_ALLOCATION_ENABLED, DEFAULT_PROFILING_ALLOCATION_ENABLED);
    profilingHeapEnabled =
        configProvider.getBoolean(PROFILING_HEAP_ENABLED, DEFAULT_PROFILING_HEAP_ENABLED);
    profilingAgentless =
        configProvider.getBoolean(PROFILING_AGENTLESS, DEFAULT_PROFILING_AGENTLESS);
    profilingLegacyTracingIntegrationEnabled =
        configProvider.getBoolean(
            PROFILING_LEGACY_TRACING_INTEGRATION, DEFAULT_PROFILING_LEGACY_TRACING_INTEGRATION);
    profilingUrl = configProvider.getString(PROFILING_URL);

    if (tmpApiKey == null) {
      final String oldProfilingApiKeyFile = configProvider.getString(PROFILING_API_KEY_FILE_OLD);
      tmpApiKey = System.getenv(propertyNameToEnvironmentVariableName(PROFILING_API_KEY_OLD));
      if (oldProfilingApiKeyFile != null) {
        try {
          tmpApiKey =
              new String(
                      Files.readAllBytes(Paths.get(oldProfilingApiKeyFile)), StandardCharsets.UTF_8)
                  .trim();
        } catch (final IOException e) {
          log.error("Cannot read API key from file {}, skipping", oldProfilingApiKeyFile, e);
        }
      }
    }
    if (tmpApiKey == null) {
      final String veryOldProfilingApiKeyFile =
          configProvider.getString(PROFILING_API_KEY_FILE_VERY_OLD);
      tmpApiKey = System.getenv(propertyNameToEnvironmentVariableName(PROFILING_API_KEY_VERY_OLD));
      if (veryOldProfilingApiKeyFile != null) {
        try {
          tmpApiKey =
              new String(
                      Files.readAllBytes(Paths.get(veryOldProfilingApiKeyFile)),
                      StandardCharsets.UTF_8)
                  .trim();
        } catch (final IOException e) {
          log.error("Cannot read API key from file {}, skipping", veryOldProfilingApiKeyFile, e);
        }
      }
    }

    profilingTags = configProvider.getMergedMap(PROFILING_TAGS);
    profilingStartDelay =
        configProvider.getInteger(PROFILING_START_DELAY, DEFAULT_PROFILING_START_DELAY);
    profilingStartForceFirst =
        configProvider.getBoolean(PROFILING_START_FORCE_FIRST, DEFAULT_PROFILING_START_FORCE_FIRST);
    profilingUploadPeriod =
        configProvider.getInteger(PROFILING_UPLOAD_PERIOD, DEFAULT_PROFILING_UPLOAD_PERIOD);
    profilingTemplateOverrideFile = configProvider.getString(PROFILING_TEMPLATE_OVERRIDE_FILE);
    profilingUploadTimeout =
        configProvider.getInteger(PROFILING_UPLOAD_TIMEOUT, DEFAULT_PROFILING_UPLOAD_TIMEOUT);
    profilingUploadCompression =
        configProvider.getString(
            PROFILING_UPLOAD_COMPRESSION, DEFAULT_PROFILING_UPLOAD_COMPRESSION);
    profilingProxyHost = configProvider.getString(PROFILING_PROXY_HOST);
    profilingProxyPort =
        configProvider.getInteger(PROFILING_PROXY_PORT, DEFAULT_PROFILING_PROXY_PORT);
    profilingProxyUsername = configProvider.getString(PROFILING_PROXY_USERNAME);
    profilingProxyPassword = configProvider.getString(PROFILING_PROXY_PASSWORD);

    profilingExceptionSampleLimit =
        configProvider.getInteger(
            PROFILING_EXCEPTION_SAMPLE_LIMIT, DEFAULT_PROFILING_EXCEPTION_SAMPLE_LIMIT);
    profilingExceptionHistogramTopItems =
        configProvider.getInteger(
            PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS,
            DEFAULT_PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS);
    profilingExceptionHistogramMaxCollectionSize =
        configProvider.getInteger(
            PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE,
            DEFAULT_PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE);

    profilingExcludeAgentThreads = configProvider.getBoolean(PROFILING_EXCLUDE_AGENT_THREADS, true);

    // code hotspots are disabled by default because of potential perf overhead they can incur
    profilingHotspotsEnabled = configProvider.getBoolean(PROFILING_HOTSPOTS_ENABLED, false);

    appSecEnabled = configProvider.getBoolean(APPSEC_ENABLED, DEFAULT_APPSEC_ENABLED);

    jdbcPreparedStatementClassName =
        configProvider.getString(JDBC_PREPARED_STATEMENT_CLASS_NAME, "");

    jdbcConnectionClassName = configProvider.getString(JDBC_CONNECTION_CLASS_NAME, "");

    kafkaClientPropagationEnabled =
        configProvider.getBoolean(
            KAFKA_CLIENT_PROPAGATION_ENABLED, DEFAULT_KAFKA_CLIENT_PROPAGATION_ENABLED);

    kafkaClientPropagationDisabledTopics =
        tryMakeImmutableSet(configProvider.getList(KAFKA_CLIENT_PROPAGATION_DISABLED_TOPICS));

    kafkaClientBase64DecodingEnabled =
        configProvider.getBoolean(KAFKA_CLIENT_BASE64_DECODING_ENABLED, false);

    jmsPropagationEnabled =
        configProvider.getBoolean(JMS_PROPAGATION_ENABLED, DEFAULT_JMS_PROPAGATION_ENABLED);

    jmsPropagationDisabledTopics =
        tryMakeImmutableSet(configProvider.getList(JMS_PROPAGATION_DISABLED_TOPICS));

    jmsPropagationDisabledQueues =
        tryMakeImmutableSet(configProvider.getList(JMS_PROPAGATION_DISABLED_QUEUES));

    awsSqsAsSeparateService =
        configProvider.getBoolean(AWS_SQS_AS_SEPARATE_SERVICE, DEFAULT_AWS_SQS_AS_SEPARATE_SERVICE);

    rabbitPropagationEnabled =
        configProvider.getBoolean(RABBIT_PROPAGATION_ENABLED, DEFAULT_RABBIT_PROPAGATION_ENABLED);

    rabbitPropagationDisabledQueues =
        tryMakeImmutableSet(configProvider.getList(RABBIT_PROPAGATION_DISABLED_QUEUES));

    rabbitPropagationDisabledExchanges =
        tryMakeImmutableSet(configProvider.getList(RABBIT_PROPAGATION_DISABLED_EXCHANGES));

    grpcIgnoredOutboundMethods =
        tryMakeImmutableSet(configProvider.getList(GRPC_IGNORED_OUTBOUND_METHODS));
    grpcServerTrimPackageResource =
        configProvider.getBoolean(GRPC_SERVER_TRIM_PACKAGE_RESOURCE, false);

    hystrixTagsEnabled = configProvider.getBoolean(HYSTRIX_TAGS_ENABLED, false);
    hystrixMeasuredEnabled = configProvider.getBoolean(HYSTRIX_MEASURED_ENABLED, false);

    igniteCacheIncludeKeys = configProvider.getBoolean(IGNITE_CACHE_INCLUDE_KEYS, false);

    osgiSearchDepth = configProvider.getInteger(OSGI_SEARCH_DEPTH, 1);

    playReportHttpStatus = configProvider.getBoolean(PLAY_REPORT_HTTP_STATUS, false);

    servletPrincipalEnabled = configProvider.getBoolean(SERVLET_PRINCIPAL_ENABLED, false);

    servletAsyncTimeoutError = configProvider.getBoolean(SERVLET_ASYNC_TIMEOUT_ERROR, true);

    tempJarsCleanOnBoot =
        configProvider.getBoolean(TEMP_JARS_CLEAN_ON_BOOT, false) && isWindowsOS();

    debugEnabled = isDebugMode();

    internalExitOnFailure = configProvider.getBoolean(INTERNAL_EXIT_ON_FAILURE, false);

    resolverUseLoadClassEnabled = configProvider.getBoolean(RESOLVER_USE_LOADCLASS, true);

    // Setting this last because we have a few places where this can come from
    apiKey = tmpApiKey;

    if (profilingAgentless && apiKey == null) {
      log.warn(
          "Agentless profiling activated but no api key provided. Profile uploading will likely fail");
    }

    log.debug("New instance: {}", this);
  }

  public long getStartTimeMillis() {
    return startTimeMillis;
  }

  public String getRuntimeId() {
    return runtimeId;
  }

  public String getApiKey() {
    return apiKey;
  }

  public String getSite() {
    return site;
  }

  public String getServiceName() {
    return serviceName;
  }

  public boolean isServiceNameSetByUser() {
    return serviceNameSetByUser;
  }

  public String getRootContextServiceName() {
    return rootContextServiceName;
  }

  public boolean isTraceEnabled() {
    return traceEnabled;
  }

  public boolean isIntegrationsEnabled() {
    return integrationsEnabled;
  }

  public String getWriterType() {
    return writerType;
  }

  public boolean isAgentConfiguredUsingDefault() {
    return agentConfiguredUsingDefault;
  }

  public String getAgentUrl() {
    return agentUrl;
  }

  public String getAgentHost() {
    return agentHost;
  }

  public int getAgentPort() {
    return agentPort;
  }

  public String getAgentUnixDomainSocket() {
    return agentUnixDomainSocket;
  }

  public int getAgentTimeout() {
    return agentTimeout;
  }

  public Set<String> getNoProxyHosts() {
    return noProxyHosts;
  }

  public boolean isPrioritySamplingEnabled() {
    return prioritySamplingEnabled;
  }

  public String getPrioritySamplingForce() {
    return prioritySamplingForce;
  }

  public boolean isTraceResolverEnabled() {
    return traceResolverEnabled;
  }

  public Map<String, String> getServiceMapping() {
    return serviceMapping;
  }

  public List<String> getExcludedClasses() {
    return excludedClasses;
  }

  public Map<String, String> getHeaderTags() {
    return headerTags;
  }

  public Map<String, String> getHttpServerPathResourceNameMapping() {
    return httpServerPathResourceNameMapping;
  }

  public BitSet getHttpServerErrorStatuses() {
    return httpServerErrorStatuses;
  }

  public BitSet getHttpClientErrorStatuses() {
    return httpClientErrorStatuses;
  }

  public boolean isHttpServerTagQueryString() {
    return httpServerTagQueryString;
  }

  public boolean isHttpServerRawQueryString() {
    return httpServerRawQueryString;
  }

  public boolean isHttpServerRawResource() {
    return httpServerRawResource;
  }

  public boolean isHttpServerRouteBasedNaming() {
    return httpServerRouteBasedNaming;
  }

  public boolean isHttpClientTagQueryString() {
    return httpClientTagQueryString;
  }

  public boolean isHttpClientSplitByDomain() {
    return httpClientSplitByDomain;
  }

  public boolean isDbClientSplitByInstance() {
    return dbClientSplitByInstance;
  }

  public Set<String> getSplitByTags() {
    return splitByTags;
  }

  public int getScopeDepthLimit() {
    return scopeDepthLimit;
  }

  public boolean isScopeStrictMode() {
    return scopeStrictMode;
  }

  public boolean isScopeInheritAsyncPropagation() {
    return scopeInheritAsyncPropagation;
  }

  public int getPartialFlushMinSpans() {
    return partialFlushMinSpans;
  }

  public boolean isTraceStrictWritesEnabled() {
    return traceStrictWritesEnabled;
  }

  public boolean isRuntimeContextFieldInjection() {
    return runtimeContextFieldInjection;
  }

  public boolean isSerialVersionUIDFieldInjection() {
    return serialVersionUIDFieldInjection;
  }

  public boolean isLogExtractHeaderNames() {
    return logExtractHeaderNames;
  }

  public Set<PropagationStyle> getPropagationStylesToExtract() {
    return propagationStylesToExtract;
  }

  public Set<PropagationStyle> getPropagationStylesToInject() {
    return propagationStylesToInject;
  }

  public int getDogStatsDStartDelay() {
    return dogStatsDStartDelay;
  }

  public boolean isJmxFetchEnabled() {
    return jmxFetchEnabled;
  }

  public String getJmxFetchConfigDir() {
    return jmxFetchConfigDir;
  }

  public List<String> getJmxFetchConfigs() {
    return jmxFetchConfigs;
  }

  public List<String> getJmxFetchMetricsConfigs() {
    return jmxFetchMetricsConfigs;
  }

  public Integer getJmxFetchCheckPeriod() {
    return jmxFetchCheckPeriod;
  }

  public Integer getJmxFetchRefreshBeansPeriod() {
    return jmxFetchRefreshBeansPeriod;
  }

  public Integer getJmxFetchInitialRefreshBeansPeriod() {
    return jmxFetchInitialRefreshBeansPeriod;
  }

  public String getJmxFetchStatsdHost() {
    return jmxFetchStatsdHost;
  }

  public Integer getJmxFetchStatsdPort() {
    return jmxFetchStatsdPort;
  }

  public boolean isHealthMetricsEnabled() {
    return healthMetricsEnabled;
  }

  public String getHealthMetricsStatsdHost() {
    return healthMetricsStatsdHost;
  }

  public Integer getHealthMetricsStatsdPort() {
    return healthMetricsStatsdPort;
  }

  public boolean isPerfMetricsEnabled() {
    return perfMetricsEnabled;
  }

  public boolean isTracerMetricsEnabled() {
    return tracerMetricsEnabled;
  }

  public boolean isTracerMetricsBufferingEnabled() {
    return tracerMetricsBufferingEnabled;
  }

  public int getTracerMetricsMaxAggregates() {
    return tracerMetricsMaxAggregates;
  }

  public int getTracerMetricsMaxPending() {
    return tracerMetricsMaxPending;
  }

  public boolean isLogsInjectionEnabled() {
    return logsInjectionEnabled;
  }

  public boolean isLogsMDCTagsInjectionEnabled() {
    return logsMDCTagsInjectionEnabled;
  }

  public boolean isReportHostName() {
    return reportHostName;
  }

  public String getTraceAnnotations() {
    return traceAnnotations;
  }

  public String getTraceMethods() {
    return traceMethods;
  }

  public boolean isTraceExecutorsAll() {
    return traceExecutorsAll;
  }

  public List<String> getTraceExecutors() {
    return traceExecutors;
  }

  public boolean isTraceAnalyticsEnabled() {
    return traceAnalyticsEnabled;
  }

  public Map<String, String> getTraceSamplingServiceRules() {
    return traceSamplingServiceRules;
  }

  public Map<String, String> getTraceSamplingOperationRules() {
    return traceSamplingOperationRules;
  }

  public Double getTraceSampleRate() {
    return traceSampleRate;
  }

  public int getTraceRateLimit() {
    return traceRateLimit;
  }

  public boolean isProfilingEnabled() {
    return profilingEnabled;
  }

  public boolean isProfilingAllocationEnabled() {
    return profilingAllocationEnabled;
  }

  public boolean isProfilingHeapEnabled() {
    return profilingHeapEnabled;
  }

  public boolean isProfilingAgentless() {
    return profilingAgentless;
  }

  public int getProfilingStartDelay() {
    return profilingStartDelay;
  }

  public boolean isProfilingStartForceFirst() {
    return profilingStartForceFirst;
  }

  public int getProfilingUploadPeriod() {
    return profilingUploadPeriod;
  }

  public String getProfilingTemplateOverrideFile() {
    return profilingTemplateOverrideFile;
  }

  public int getProfilingUploadTimeout() {
    return profilingUploadTimeout;
  }

  public String getProfilingUploadCompression() {
    return profilingUploadCompression;
  }

  public String getProfilingProxyHost() {
    return profilingProxyHost;
  }

  public int getProfilingProxyPort() {
    return profilingProxyPort;
  }

  public String getProfilingProxyUsername() {
    return profilingProxyUsername;
  }

  public String getProfilingProxyPassword() {
    return profilingProxyPassword;
  }

  public int getProfilingExceptionSampleLimit() {
    return profilingExceptionSampleLimit;
  }

  public int getProfilingExceptionHistogramTopItems() {
    return profilingExceptionHistogramTopItems;
  }

  public int getProfilingExceptionHistogramMaxCollectionSize() {
    return profilingExceptionHistogramMaxCollectionSize;
  }

  public boolean isProfilingExcludeAgentThreads() {
    return profilingExcludeAgentThreads;
  }

  public boolean isProfilingHotspotsEnabled() {
    return profilingHotspotsEnabled;
  }

  public boolean isProfilingLegacyTracingIntegrationEnabled() {
    return profilingLegacyTracingIntegrationEnabled;
  }

  public boolean isAppSecEnabled() {
    return appSecEnabled;
  }

  public boolean isKafkaClientPropagationEnabled() {
    return kafkaClientPropagationEnabled;
  }

  public boolean isKafkaClientPropagationDisabledForTopic(String topic) {
    return null != topic && kafkaClientPropagationDisabledTopics.contains(topic);
  }

  public boolean isJMSPropagationEnabled() {
    return jmsPropagationEnabled;
  }

  public boolean isJMSPropagationDisabledForDestination(final String queueOrTopic) {
    return null != queueOrTopic
        && (jmsPropagationDisabledQueues.contains(queueOrTopic)
            || jmsPropagationDisabledTopics.contains(queueOrTopic));
  }

  public boolean isKafkaClientBase64DecodingEnabled() {
    return kafkaClientBase64DecodingEnabled;
  }

  public boolean isAwsSqsAsSeparateService() {
    return awsSqsAsSeparateService;
  }

  public boolean isRabbitPropagationEnabled() {
    return rabbitPropagationEnabled;
  }

  public boolean isRabbitPropagationDisabledForDestination(final String queueOrExchange) {
    return null != queueOrExchange
        && (rabbitPropagationDisabledQueues.contains(queueOrExchange)
            || rabbitPropagationDisabledExchanges.contains(queueOrExchange));
  }

  public boolean isHystrixTagsEnabled() {
    return hystrixTagsEnabled;
  }

  public boolean isHystrixMeasuredEnabled() {
    return hystrixMeasuredEnabled;
  }

  public boolean isIgniteCacheIncludeKeys() {
    return igniteCacheIncludeKeys;
  }

  public int getOsgiSearchDepth() {
    return osgiSearchDepth;
  }

  public boolean getPlayReportHttpStatus() {
    return playReportHttpStatus;
  }

  public boolean isServletPrincipalEnabled() {
    return servletPrincipalEnabled;
  }

  public boolean isServletAsyncTimeoutError() {
    return servletAsyncTimeoutError;
  }

  public boolean isTempJarsCleanOnBoot() {
    return tempJarsCleanOnBoot;
  }

  public boolean isTraceAgentV05Enabled() {
    return traceAgentV05Enabled;
  }

  public boolean isDebugEnabled() {
    return debugEnabled;
  }

  public String getConfigFile() {
    return configFile;
  }

  public IdGenerationStrategy getIdGenerationStrategy() {
    return idGenerationStrategy;
  }

  public boolean isInternalExitOnFailure() {
    return internalExitOnFailure;
  }

  public boolean isResolverUseLoadClassEnabled() {
    return resolverUseLoadClassEnabled;
  }

  public String getJdbcPreparedStatementClassName() {
    return jdbcPreparedStatementClassName;
  }

  public String getJdbcConnectionClassName() {
    return jdbcConnectionClassName;
  }

  public Set<String> getGrpcIgnoredOutboundMethods() {
    return grpcIgnoredOutboundMethods;
  }

  public boolean isGrpcServerTrimPackageResource() {
    return grpcServerTrimPackageResource;
  }

  /** @return A map of tags to be applied only to the local application root span. */
  public Map<String, String> getLocalRootSpanTags() {
    final Map<String, String> runtimeTags = getRuntimeTags();
    final Map<String, String> result = new HashMap<>(runtimeTags);
    result.put(LANGUAGE_TAG_KEY, LANGUAGE_TAG_VALUE);

    if (reportHostName) {
      final String hostName = getHostName();
      if (null != hostName && !hostName.isEmpty()) {
        result.put(INTERNAL_HOST_NAME, hostName);
      }
    }

    return Collections.unmodifiableMap(result);
  }

  public WellKnownTags getWellKnownTags() {
    return new WellKnownTags(
        getRuntimeId(), reportHostName ? getHostName() : "", getEnv(), serviceName, getVersion());
  }

  public Set<String> getMetricsIgnoredResources() {
    return tryMakeImmutableSet(configProvider.getList(TRACER_METRICS_IGNORED_RESOURCES));
  }

  public String getEnv() {
    // intentionally not thread safe
    if (env == null) {
      env = getMergedSpanTags().get("env");
      if (env == null) {
        env = "";
      }
    }

    return env;
  }

  public String getVersion() {
    // intentionally not thread safe
    if (version == null) {
      version = getMergedSpanTags().get("version");
      if (version == null) {
        version = "";
      }
    }

    return version;
  }

  public Map<String, String> getMergedSpanTags() {
    // Do not include runtimeId into span tags: we only want that added to the root span
    final Map<String, String> result = newHashMap(getGlobalTags().size() + spanTags.size());
    result.putAll(getGlobalTags());
    result.putAll(spanTags);
    return Collections.unmodifiableMap(result);
  }

  public Map<String, String> getMergedJmxTags() {
    final Map<String, String> runtimeTags = getRuntimeTags();
    final Map<String, String> result =
        newHashMap(
            getGlobalTags().size() + jmxTags.size() + runtimeTags.size() + 1 /* for serviceName */);
    result.putAll(getGlobalTags());
    result.putAll(jmxTags);
    result.putAll(runtimeTags);
    // service name set here instead of getRuntimeTags because apm already manages the service tag
    // and may chose to override it.
    // Additionally, infra/JMX metrics require `service` rather than APM's `service.name` tag
    result.put(SERVICE_TAG, serviceName);
    return Collections.unmodifiableMap(result);
  }

  public Map<String, String> getMergedProfilingTags() {
    final Map<String, String> runtimeTags = getRuntimeTags();
    final String host = getHostName();
    final Map<String, String> result =
        newHashMap(
            getGlobalTags().size()
                + profilingTags.size()
                + runtimeTags.size()
                + 3 /* for serviceName and host and language */);
    result.put(HOST_TAG, host); // Host goes first to allow to override it
    result.putAll(getGlobalTags());
    result.putAll(profilingTags);
    result.putAll(runtimeTags);
    // service name set here instead of getRuntimeTags because apm already manages the service tag
    // and may chose to override it.
    result.put(SERVICE_TAG, serviceName);
    result.put(LANGUAGE_TAG_KEY, LANGUAGE_TAG_VALUE);
    return Collections.unmodifiableMap(result);
  }

  /**
   * Returns the sample rate for the specified instrumentation or {@link
   * ConfigDefaults#DEFAULT_ANALYTICS_SAMPLE_RATE} if none specified.
   */
  public float getInstrumentationAnalyticsSampleRate(final String... aliases) {
    for (final String alias : aliases) {
      final String configKey = alias + ".analytics.sample-rate";
      final Float rate = configProvider.getFloat("trace." + configKey, configKey);
      if (null != rate) {
        return rate;
      }
    }
    return DEFAULT_ANALYTICS_SAMPLE_RATE;
  }

  /**
   * Provide 'global' tags, i.e. tags set everywhere. We have to support old (dd.trace.global.tags)
   * version of this setting if new (dd.tags) version has not been specified.
   */
  private Map<String, String> getGlobalTags() {
    return tags;
  }

  /**
   * Return a map of tags required by the datadog backend to link runtime metrics (i.e. jmx) and
   * traces.
   *
   * <p>These tags must be applied to every runtime metrics and placed on the root span of every
   * trace.
   *
   * @return A map of tag-name -> tag-value
   */
  private Map<String, String> getRuntimeTags() {
    final Map<String, String> result = newHashMap(2);
    result.put(RUNTIME_ID_TAG, runtimeId);
    return Collections.unmodifiableMap(result);
  }

  public String getFinalProfilingUrl() {
    if (profilingUrl != null) {
      // when profilingUrl is set we use it regardless of apiKey/agentless config
      return profilingUrl;
    } else if (profilingAgentless) {
      // when agentless profiling is turned on we send directly to our intake
      return "https://intake.profile." + site + "/v1/input";
    } else {
      // when profilingUrl and agentless are not set we send to the dd trace agent running locally
      return "http://" + agentHost + ":" + agentPort + "/profiling/v1/input";
    }
  }

  public boolean isIntegrationEnabled(
      final Iterable<String> integrationNames, final boolean defaultEnabled) {
    return isEnabled(integrationNames, "integration.", ".enabled", defaultEnabled);
  }

  public boolean isIntegrationShortCutMatchingEnabled(
      final Iterable<String> integrationNames, final boolean defaultEnabled) {
    return isEnabled(
        integrationNames, "integration.", ".matching.shortcut.enabled", defaultEnabled);
  }

  public boolean isJmxFetchIntegrationEnabled(
      final Iterable<String> integrationNames, final boolean defaultEnabled) {
    return isEnabled(integrationNames, "jmxfetch.", ".enabled", defaultEnabled);
  }

  public boolean isRuleEnabled(final String name) {
    return isRuleEnabled(name, true);
  }

  public boolean isRuleEnabled(final String name, boolean defaultEnabled) {
    boolean enabled = configProvider.getBoolean("trace." + name + ".enabled", defaultEnabled);
    boolean lowerEnabled =
        configProvider.getBoolean("trace." + name.toLowerCase() + ".enabled", defaultEnabled);
    return defaultEnabled ? enabled && lowerEnabled : enabled || lowerEnabled;
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
    return isEnabled(Arrays.asList(integrationNames), "", ".e2e.duration.enabled", defaultEnabled);
  }

  public boolean isTraceAnalyticsIntegrationEnabled(
      final SortedSet<String> integrationNames, final boolean defaultEnabled) {
    return isEnabled(integrationNames, "", ".analytics.enabled", defaultEnabled);
  }

  public boolean isTraceAnalyticsIntegrationEnabled(
      final boolean defaultEnabled, final String... integrationNames) {
    return isEnabled(Arrays.asList(integrationNames), "", ".analytics.enabled", defaultEnabled);
  }

  public <T extends Enum<T>> T getEnumValue(
      final String name, final Class<T> type, final T defaultValue) {
    return configProvider.getEnum(name, type, defaultValue);
  }

  private static boolean isDebugMode() {
    final String tracerDebugLevelSysprop = "dd.trace.debug";
    final String tracerDebugLevelProp = System.getProperty(tracerDebugLevelSysprop);

    if (tracerDebugLevelProp != null) {
      return Boolean.parseBoolean(tracerDebugLevelProp);
    }

    final String tracerDebugLevelEnv = System.getenv(toEnvVar(tracerDebugLevelSysprop));

    if (tracerDebugLevelEnv != null) {
      return Boolean.parseBoolean(tracerDebugLevelEnv);
    }
    return false;
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

  private boolean isEnabled(
      final Iterable<String> integrationNames,
      final String settingPrefix,
      final String settingSuffix,
      final boolean defaultEnabled) {
    // If default is enabled, we want to disable individually.
    // If default is disabled, we want to enable individually.
    boolean anyEnabled = defaultEnabled;
    for (final String name : integrationNames) {
      final String configKey = settingPrefix + name + settingSuffix;
      final boolean configEnabled =
          configProvider.getBoolean("trace." + configKey, defaultEnabled, configKey);
      if (defaultEnabled) {
        anyEnabled &= configEnabled;
      } else {
        anyEnabled |= configEnabled;
      }
    }
    return anyEnabled;
  }

  /**
   * Calls configProvider.getString(String, String) and converts the result to a set of strings
   * splitting by space or comma.
   */
  private Set<PropagationStyle> getPropagationStyleSetSettingFromEnvironmentOrDefault(
      final String name, final String defaultValue) {
    final String value = configProvider.getString(name, defaultValue);
    Set<PropagationStyle> result =
        convertStringSetToPropagationStyleSet(parseStringIntoSetOfNonEmptyStrings(value));

    if (result.isEmpty()) {
      // Treat empty parsing result as no value and use default instead
      result =
          convertStringSetToPropagationStyleSet(parseStringIntoSetOfNonEmptyStrings(defaultValue));
    }

    return result;
  }

  private static final String PREFIX = "dd.";

  /**
   * Converts the property name, e.g. 'service.name' into a public system property name, e.g.
   * `dd.service.name`.
   *
   * @param setting The setting name, e.g. `service.name`
   * @return The public facing system property name
   */
  @Nonnull
  private static String propertyNameToSystemPropertyName(final String setting) {
    return PREFIX + setting;
  }

  @Nonnull
  private static Map<String, String> newHashMap(final int size) {
    return new HashMap<>(size + 1, 1f);
  }

  /**
   * @param map
   * @param propNames
   * @return new unmodifiable copy of {@param map} where properties are overwritten from environment
   */
  @Nonnull
  private Map<String, String> getMapWithPropertiesDefinedByEnvironment(
      @Nonnull final Map<String, String> map, @Nonnull final String... propNames) {
    final Map<String, String> res = new HashMap<>(map);
    for (final String propName : propNames) {
      final String val = configProvider.getString(propName);
      if (val != null) {
        res.put(propName, val);
      }
    }
    return Collections.unmodifiableMap(res);
  }

  @Nonnull
  private static Set<String> parseStringIntoSetOfNonEmptyStrings(final String str) {
    // Using LinkedHashSet to preserve original string order
    final Set<String> result = new LinkedHashSet<>();
    // Java returns single value when splitting an empty string. We do not need that value, so
    // we need to throw it out.
    int start = 0;
    int i = 0;
    for (; i < str.length(); ++i) {
      char c = str.charAt(i);
      if (Character.isWhitespace(c) || c == ',') {
        if (i - start - 1 > 0) {
          result.add(str.substring(start, i));
        }
        start = i + 1;
      }
    }
    if (i - start - 1 > 0) {
      result.add(str.substring(start));
    }
    return Collections.unmodifiableSet(result);
  }

  @Nonnull
  private static Set<PropagationStyle> convertStringSetToPropagationStyleSet(
      final Set<String> input) {
    // Using LinkedHashSet to preserve original string order
    final Set<PropagationStyle> result = new LinkedHashSet<>();
    for (final String value : input) {
      try {
        result.add(PropagationStyle.valueOf(value.toUpperCase()));
      } catch (final IllegalArgumentException e) {
        log.debug("Cannot recognize config string value: {}, {}", value, PropagationStyle.class);
      }
    }
    return Collections.unmodifiableSet(result);
  }

  @SuppressForbidden
  private static String findConfigurationFile() {
    String configurationFilePath =
        System.getProperty(propertyNameToSystemPropertyName(CONFIGURATION_FILE));
    if (null == configurationFilePath) {
      configurationFilePath =
          System.getenv(propertyNameToEnvironmentVariableName(CONFIGURATION_FILE));
    }
    if (null != configurationFilePath && !configurationFilePath.isEmpty()) {
      int homeIndex = configurationFilePath.indexOf('~');
      if (homeIndex != -1) {

        configurationFilePath =
            configurationFilePath.substring(0, homeIndex)
                + System.getProperty("user.home")
                + configurationFilePath.substring(homeIndex + 1);
      }
      final File configurationFile = new File(configurationFilePath);
      if (!configurationFile.exists()) {
        return configurationFilePath;
      }
    }
    return "no config file present";
  }

  /** Returns the detected hostname. First tries locally, then using DNS */
  public static String getHostName() {
    String possibleHostname;

    // Try environment variable.  This works in almost all environments
    if (isWindowsOS()) {
      possibleHostname = System.getenv("COMPUTERNAME");
    } else {
      possibleHostname = System.getenv("HOSTNAME");
    }

    if (possibleHostname != null && !possibleHostname.isEmpty()) {
      log.debug("Determined hostname from environment variable");
      return possibleHostname.trim();
    }

    // Try hostname command
    try (final BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(Runtime.getRuntime().exec("hostname").getInputStream()))) {
      possibleHostname = reader.readLine();
    } catch (final Exception ignore) {
      // Ignore.  Hostname command is not always available
    }

    if (possibleHostname != null && !possibleHostname.isEmpty()) {
      log.debug("Determined hostname from hostname command");
      return possibleHostname.trim();
    }

    // From DNS
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (final UnknownHostException e) {
      // If we are not able to detect the hostname we do not throw an exception.
    }

    return null;
  }

  private static boolean isWindowsOS() {
    return System.getProperty("os.name").startsWith("Windows");
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
   * <p>Config keys for use in Properties instance construction can be found in {@link
   * GeneralConfig} and {@link TracerConfig}.
   *
   * @deprecated
   */
  @Deprecated
  public static Config get(final Properties properties) {
    if (properties == null || properties.isEmpty()) {
      return INSTANCE;
    } else {
      return new Config(INSTANCE.runtimeId, ConfigProvider.withPropertiesOverride(properties));
    }
  }

  @Override
  public String toString() {
    return "Config{"
        + "runtimeId='"
        + runtimeId
        + '\''
        + ", apiKey="
        + (apiKey == null ? "null" : "****")
        + ", site='"
        + site
        + '\''
        + ", serviceName='"
        + serviceName
        + '\''
        + ", serviceNameSetByUser="
        + serviceNameSetByUser
        + ", rootContextServiceName="
        + rootContextServiceName
        + ", traceEnabled="
        + traceEnabled
        + ", integrationsEnabled="
        + integrationsEnabled
        + ", writerType='"
        + writerType
        + '\''
        + ", agentConfiguredUsingDefault="
        + agentConfiguredUsingDefault
        + ", agentUrl='"
        + agentUrl
        + '\''
        + ", agentHost='"
        + agentHost
        + '\''
        + ", agentPort="
        + agentPort
        + ", agentUnixDomainSocket='"
        + agentUnixDomainSocket
        + '\''
        + ", agentTimeout="
        + agentTimeout
        + ", noProxyHosts="
        + noProxyHosts
        + ", prioritySamplingEnabled="
        + prioritySamplingEnabled
        + ", prioritySamplingForce='"
        + prioritySamplingForce
        + '\''
        + ", traceResolverEnabled="
        + traceResolverEnabled
        + ", serviceMapping="
        + serviceMapping
        + ", tags="
        + tags
        + ", spanTags="
        + spanTags
        + ", jmxTags="
        + jmxTags
        + ", excludedClasses="
        + excludedClasses
        + ", headerTags="
        + headerTags
        + ", httpServerErrorStatuses="
        + httpServerErrorStatuses
        + ", httpClientErrorStatuses="
        + httpClientErrorStatuses
        + ", httpServerTagQueryString="
        + httpServerTagQueryString
        + ", httpServerRawQueryString="
        + httpServerRawQueryString
        + ", httpServerRawResource="
        + httpServerRawResource
        + ", httpServerRouteBasedNaming="
        + httpServerRouteBasedNaming
        + ", httpServerPathResourceNameMapping="
        + httpServerPathResourceNameMapping
        + ", httpClientTagQueryString="
        + httpClientTagQueryString
        + ", httpClientSplitByDomain="
        + httpClientSplitByDomain
        + ", dbClientSplitByInstance="
        + dbClientSplitByInstance
        + ", splitByTags="
        + splitByTags
        + ", scopeDepthLimit="
        + scopeDepthLimit
        + ", scopeStrictMode="
        + scopeStrictMode
        + ", scopeInheritAsyncPropagation="
        + scopeInheritAsyncPropagation
        + ", partialFlushMinSpans="
        + partialFlushMinSpans
        + ", traceStrictWritesEnabled="
        + traceStrictWritesEnabled
        + ", runtimeContextFieldInjection="
        + runtimeContextFieldInjection
        + ", serialVersionUIDFieldInjection="
        + serialVersionUIDFieldInjection
        + ", propagationStylesToExtract="
        + propagationStylesToExtract
        + ", propagationStylesToInject="
        + propagationStylesToInject
        + ", jmxFetchEnabled="
        + jmxFetchEnabled
        + ", dogStatsDStartDelay="
        + dogStatsDStartDelay
        + ", jmxFetchConfigDir='"
        + jmxFetchConfigDir
        + '\''
        + ", jmxFetchConfigs="
        + jmxFetchConfigs
        + ", jmxFetchMetricsConfigs="
        + jmxFetchMetricsConfigs
        + ", jmxFetchCheckPeriod="
        + jmxFetchCheckPeriod
        + ", jmxFetchInitialRefreshBeansPeriod="
        + jmxFetchInitialRefreshBeansPeriod
        + ", jmxFetchRefreshBeansPeriod="
        + jmxFetchRefreshBeansPeriod
        + ", jmxFetchStatsdHost='"
        + jmxFetchStatsdHost
        + '\''
        + ", jmxFetchStatsdPort="
        + jmxFetchStatsdPort
        + ", healthMetricsEnabled="
        + healthMetricsEnabled
        + ", healthMetricsStatsdHost='"
        + healthMetricsStatsdHost
        + '\''
        + ", healthMetricsStatsdPort="
        + healthMetricsStatsdPort
        + ", perfMetricsEnabled="
        + perfMetricsEnabled
        + ", tracerMetricsEnabled="
        + tracerMetricsEnabled
        + ", tracerMetricsBufferingEnabled="
        + tracerMetricsBufferingEnabled
        + ", tracerMetricsMaxAggregates="
        + tracerMetricsMaxAggregates
        + ", tracerMetricsMaxPending="
        + tracerMetricsMaxPending
        + ", logsInjectionEnabled="
        + logsInjectionEnabled
        + ", logsMDCTagsInjectionEnabled="
        + logsMDCTagsInjectionEnabled
        + ", reportHostName="
        + reportHostName
        + ", traceAnnotations='"
        + traceAnnotations
        + '\''
        + ", traceMethods='"
        + traceMethods
        + '\''
        + ", traceExecutorsAll="
        + traceExecutorsAll
        + ", traceExecutors="
        + traceExecutors
        + ", traceAnalyticsEnabled="
        + traceAnalyticsEnabled
        + ", traceSamplingServiceRules="
        + traceSamplingServiceRules
        + ", traceSamplingOperationRules="
        + traceSamplingOperationRules
        + ", traceSampleRate="
        + traceSampleRate
        + ", traceRateLimit="
        + traceRateLimit
        + ", profilingEnabled="
        + profilingEnabled
        + ", profilingAllocationEnabled="
        + profilingAllocationEnabled
        + ", profilingHeapEnabled="
        + profilingHeapEnabled
        + ", profilingAgentless="
        + profilingAgentless
        + ", profilingUrl='"
        + profilingUrl
        + '\''
        + ", profilingTags="
        + profilingTags
        + ", profilingStartDelay="
        + profilingStartDelay
        + ", profilingStartForceFirst="
        + profilingStartForceFirst
        + ", profilingUploadPeriod="
        + profilingUploadPeriod
        + ", profilingTemplateOverrideFile='"
        + profilingTemplateOverrideFile
        + '\''
        + ", profilingUploadTimeout="
        + profilingUploadTimeout
        + ", profilingUploadCompression='"
        + profilingUploadCompression
        + '\''
        + ", profilingProxyHost='"
        + profilingProxyHost
        + '\''
        + ", profilingProxyPort="
        + profilingProxyPort
        + ", profilingProxyUsername='"
        + profilingProxyUsername
        + '\''
        + ", profilingProxyPassword="
        + (profilingProxyPassword == null ? "null" : "****")
        + ", profilingExceptionSampleLimit="
        + profilingExceptionSampleLimit
        + ", profilingExceptionHistogramTopItems="
        + profilingExceptionHistogramTopItems
        + ", profilingExceptionHistogramMaxCollectionSize="
        + profilingExceptionHistogramMaxCollectionSize
        + ", profilingExcludeAgentThreads="
        + profilingExcludeAgentThreads
        + ", kafkaClientPropagationEnabled="
        + kafkaClientPropagationEnabled
        + ", kafkaClientPropagationDisabledTopics="
        + kafkaClientPropagationDisabledTopics
        + ", kafkaClientBase64DecodingEnabled="
        + kafkaClientBase64DecodingEnabled
        + ", jmsPropagationEnabled="
        + jmsPropagationEnabled
        + ", jmsPropagationDisabledTopics="
        + jmsPropagationDisabledTopics
        + ", jmsPropagationDisabledQueues="
        + jmsPropagationDisabledQueues
        + ", RabbitPropagationEnabled="
        + rabbitPropagationEnabled
        + ", RabbitPropagationDisabledQueues="
        + rabbitPropagationDisabledQueues
        + ", RabbitPropagationDisabledExchanges="
        + rabbitPropagationDisabledExchanges
        + ", hystrixTagsEnabled="
        + hystrixTagsEnabled
        + ", hystrixMeasuredEnabled="
        + hystrixMeasuredEnabled
        + ", igniteCacheIncludeKeys="
        + igniteCacheIncludeKeys
        + ", osgiSearchDepth="
        + osgiSearchDepth
        + ", servletPrincipalEnabled="
        + servletPrincipalEnabled
        + ", servletAsyncTimeoutError="
        + servletAsyncTimeoutError
        + ", tempJarsCleanOnBoot="
        + tempJarsCleanOnBoot
        + ", traceAgentV05Enabled="
        + traceAgentV05Enabled
        + ", debugEnabled="
        + debugEnabled
        + ", configFile='"
        + configFile
        + '\''
        + ", idGenerationStrategy="
        + idGenerationStrategy
        + ", internalExitOnFailure="
        + internalExitOnFailure
        + ", resolverUseLoadClassEnabled="
        + resolverUseLoadClassEnabled
        + ", jdbcPreparedStatementClassName='"
        + jdbcPreparedStatementClassName
        + '\''
        + ", jdbcConnectionClassName='"
        + jdbcConnectionClassName
        + '\''
        + ", grpcIgnoredOutboundMethods="
        + grpcIgnoredOutboundMethods
        + ", configProvider="
        + configProvider
        + '}';
  }
}
