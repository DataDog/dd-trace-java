package datadog.trace.api;

import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_HOST;
import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_TIMEOUT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_WRITER_TYPE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_ANALYTICS_SAMPLE_RATE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_APPSEC_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_APPSEC_REPORTING_INBAND;
import static datadog.trace.api.ConfigDefaults.DEFAULT_APPSEC_TRACE_RATE_LIMIT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_APPSEC_WAF_METRICS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CIVISIBILITY_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CLOCK_SYNC_PERIOD;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CWS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CWS_TLS_REFRESH;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DOGSTATSD_START_DELAY;
import static datadog.trace.api.ConfigDefaults.DEFAULT_GRPC_CLIENT_ERROR_STATUSES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_GRPC_SERVER_ERROR_STATUSES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HEALTH_METRICS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_CLIENT_ERROR_STATUSES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_CLIENT_SPLIT_BY_DOMAIN;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_CLIENT_TAG_QUERY_STRING;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_SERVER_ERROR_STATUSES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_SERVER_ROUTE_BASED_NAMING;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_SERVER_TAG_QUERY_STRING;
import static datadog.trace.api.ConfigDefaults.DEFAULT_INTEGRATIONS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_JMX_FETCH_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_LIMIT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_LOGS_INJECTION_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PARTIAL_FLUSH_MIN_SPANS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PERF_METRICS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PRIORITY_SAMPLING_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PRIORITY_SAMPLING_FORCE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROPAGATION_STYLE_EXTRACT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROPAGATION_STYLE_INJECT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_RUNTIME_CONTEXT_FIELD_INJECTION;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SCOPE_DEPTH_LIMIT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SCOPE_ITERATION_KEEP_ALIVE;
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
import static datadog.trace.api.config.AppSecConfig.APPSEC_IP_ADDR_HEADER;
import static datadog.trace.api.config.AppSecConfig.APPSEC_REPORTING_INBAND;
import static datadog.trace.api.config.AppSecConfig.APPSEC_REPORT_TIMEOUT_SEC;
import static datadog.trace.api.config.AppSecConfig.APPSEC_RULES_FILE;
import static datadog.trace.api.config.AppSecConfig.APPSEC_TRACE_RATE_LIMIT;
import static datadog.trace.api.config.AppSecConfig.APPSEC_WAF_METRICS;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_ENABLED;
import static datadog.trace.api.config.CwsConfig.CWS_ENABLED;
import static datadog.trace.api.config.CwsConfig.CWS_TLS_REFRESH;
import static datadog.trace.api.config.GeneralConfig.API_KEY;
import static datadog.trace.api.config.GeneralConfig.API_KEY_FILE;
import static datadog.trace.api.config.GeneralConfig.AZURE_APP_SERVICES;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_ARGS;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_HOST;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_NAMED_PIPE;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_PATH;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_PORT;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_START_DELAY;
import static datadog.trace.api.config.GeneralConfig.ENV;
import static datadog.trace.api.config.GeneralConfig.GLOBAL_TAGS;
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_STATSD_HOST;
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_STATSD_PORT;
import static datadog.trace.api.config.GeneralConfig.INTERNAL_EXIT_ON_FAILURE;
import static datadog.trace.api.config.GeneralConfig.PERF_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.RUNTIME_ID_ENABLED;
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
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_ENABLED;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_LIMIT;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_REFRESH_BEANS_PERIOD;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_START_DELAY;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_STATSD_HOST;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_STATSD_PORT;
import static datadog.trace.api.config.JmxFetchConfig.JMX_TAGS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_AGENTLESS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_AGENTLESS_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_API_KEY_FILE_OLD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_API_KEY_FILE_VERY_OLD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_API_KEY_OLD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_API_KEY_VERY_OLD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_SAMPLE_LIMIT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCEPTION_SAMPLE_LIMIT_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_EXCLUDE_AGENT_THREADS;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_FORMAT_V2_4_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_FORMAT_V2_4_ENABLED_DEFAULT;
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
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE;
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX;
import static datadog.trace.api.config.TraceInstrumentationConfig.GRPC_CLIENT_ERROR_STATUSES;
import static datadog.trace.api.config.TraceInstrumentationConfig.GRPC_IGNORED_INBOUND_METHODS;
import static datadog.trace.api.config.TraceInstrumentationConfig.GRPC_IGNORED_OUTBOUND_METHODS;
import static datadog.trace.api.config.TraceInstrumentationConfig.GRPC_SERVER_ERROR_STATUSES;
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
import static datadog.trace.api.config.TraceInstrumentationConfig.KAFKA_CLIENT_BASE64_DECODING_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.KAFKA_CLIENT_PROPAGATION_DISABLED_TOPICS;
import static datadog.trace.api.config.TraceInstrumentationConfig.LOGS_INJECTION_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.LOGS_MDC_TAGS_INJECTION_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.MESSAGE_BROKER_SPLIT_BY_DESTINATION;
import static datadog.trace.api.config.TraceInstrumentationConfig.OSGI_SEARCH_DEPTH;
import static datadog.trace.api.config.TraceInstrumentationConfig.PLAY_REPORT_HTTP_STATUS;
import static datadog.trace.api.config.TraceInstrumentationConfig.RABBIT_PROPAGATION_DISABLED_EXCHANGES;
import static datadog.trace.api.config.TraceInstrumentationConfig.RABBIT_PROPAGATION_DISABLED_QUEUES;
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
import static datadog.trace.api.config.TracerConfig.AGENT_NAMED_PIPE;
import static datadog.trace.api.config.TracerConfig.AGENT_PORT_LEGACY;
import static datadog.trace.api.config.TracerConfig.AGENT_TIMEOUT;
import static datadog.trace.api.config.TracerConfig.AGENT_UNIX_DOMAIN_SOCKET;
import static datadog.trace.api.config.TracerConfig.CLOCK_SYNC_PERIOD;
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
import static datadog.trace.api.config.TracerConfig.REQUEST_HEADER_TAGS;
import static datadog.trace.api.config.TracerConfig.RESPONSE_HEADER_TAGS;
import static datadog.trace.api.config.TracerConfig.SCOPE_DEPTH_LIMIT;
import static datadog.trace.api.config.TracerConfig.SCOPE_INHERIT_ASYNC_PROPAGATION;
import static datadog.trace.api.config.TracerConfig.SCOPE_ITERATION_KEEP_ALIVE;
import static datadog.trace.api.config.TracerConfig.SCOPE_STRICT_MODE;
import static datadog.trace.api.config.TracerConfig.SERVICE_MAPPING;
import static datadog.trace.api.config.TracerConfig.SPAN_TAGS;
import static datadog.trace.api.config.TracerConfig.SPLIT_BY_TAGS;
import static datadog.trace.api.config.TracerConfig.TRACE_AGENT_ARGS;
import static datadog.trace.api.config.TracerConfig.TRACE_AGENT_PATH;
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
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
  private final String agentNamedPipe;
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
  private final Map<String, String> requestHeaderTags;
  private final Map<String, String> responseHeaderTags;
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
  private final boolean dbClientSplitByInstanceTypeSuffix;
  private final Set<String> splitByTags;
  private final int scopeDepthLimit;
  private final boolean scopeStrictMode;
  private final boolean scopeInheritAsyncPropagation;
  private final int scopeIterationKeepAlive;
  private final int partialFlushMinSpans;
  private final boolean traceStrictWritesEnabled;
  private final boolean runtimeContextFieldInjection;
  private final boolean serialVersionUIDFieldInjection;
  private final boolean logExtractHeaderNames;
  private final Set<PropagationStyle> propagationStylesToExtract;
  private final Set<PropagationStyle> propagationStylesToInject;
  private final int clockSyncPeriod;

  private final String dogStatsDNamedPipe;
  private final int dogStatsDStartDelay;

  private final boolean jmxFetchEnabled;
  private final String jmxFetchConfigDir;
  private final List<String> jmxFetchConfigs;
  @Deprecated private final List<String> jmxFetchMetricsConfigs;
  private final Integer jmxFetchCheckPeriod;
  private final Integer jmxFetchInitialRefreshBeansPeriod;
  private final Integer jmxFetchRefreshBeansPeriod;
  private final String jmxFetchStatsdHost;
  private final Integer jmxFetchStatsdPort;
  private final boolean jmxFetchMultipleRuntimeServicesEnabled;
  private final int jmxFetchMultipleRuntimeServicesLimit;

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
  private final boolean profilingUploadSummaryOn413Enabled;

  private final boolean appSecEnabled;
  private final boolean appSecReportingInband;
  private final String appSecRulesFile;
  private final int appSecReportMinTimeout;
  private final int appSecReportMaxTimeout;
  private final String appSecIpAddrHeader;
  private final int appSecTraceRateLimit;
  private final boolean appSecWafMetrics;

  private final boolean ciVisibilityEnabled;

  private final boolean awsPropagationEnabled;
  private final boolean sqsPropagationEnabled;

  private final boolean kafkaClientPropagationEnabled;
  private final Set<String> kafkaClientPropagationDisabledTopics;
  private final boolean kafkaClientBase64DecodingEnabled;

  private final boolean jmsPropagationEnabled;
  private final Set<String> jmsPropagationDisabledTopics;
  private final Set<String> jmsPropagationDisabledQueues;

  private final boolean rabbitPropagationEnabled;
  private final Set<String> rabbitPropagationDisabledQueues;
  private final Set<String> rabbitPropagationDisabledExchanges;

  private final boolean messageBrokerSplitByDestination;

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
  private final String configFileStatus;

  private final IdGenerationStrategy idGenerationStrategy;

  private final boolean internalExitOnFailure;

  private final boolean resolverUseLoadClassEnabled;

  private final String jdbcPreparedStatementClassName;
  private final String jdbcConnectionClassName;

  private final Set<String> grpcIgnoredInboundMethods;
  private final Set<String> grpcIgnoredOutboundMethods;
  private final boolean grpcServerTrimPackageResource;
  private final BitSet grpcServerErrorStatuses;
  private final BitSet grpcClientErrorStatuses;

  private final boolean cwsEnabled;
  private final int cwsTlsRefresh;

  private final boolean azureAppServices;
  private final String traceAgentPath;
  private final List<String> traceAgentArgs;
  private final String dogStatsDPath;
  private final List<String> dogStatsDArgs;

  private String env;
  private String version;

  private final ConfigProvider configProvider;

  // Read order: System Properties -> Env Variables, [-> properties file], [-> default value]
  private Config() {
    this(ConfigProvider.createDefault());
  }

  private Config(final ConfigProvider configProvider) {
    this.configProvider = configProvider;
    configFileStatus = configProvider.getConfigFileStatus();
    runtimeId =
        null != INSTANCE
            ? INSTANCE.runtimeId
            : configProvider.getBoolean(RUNTIME_ID_ENABLED, true)
                ? UUID.randomUUID().toString()
                : "";

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

    agentNamedPipe = configProvider.getString(AGENT_NAMED_PIPE);

    agentConfiguredUsingDefault =
        agentHostFromEnvironment == null
            && agentPortFromEnvironment < 0
            && unixSocketFromEnvironment == null
            && agentNamedPipe == null;

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

    if (isEnabled(false, HEADER_TAGS, ".legacy.parsing.enabled")) {
      requestHeaderTags = configProvider.getMergedMap(HEADER_TAGS);
      responseHeaderTags = Collections.emptyMap();
      if (configProvider.isSet(REQUEST_HEADER_TAGS)) {
        logIngoredSettingWarning(REQUEST_HEADER_TAGS, HEADER_TAGS, ".legacy.parsing.enabled");
      }
      if (configProvider.isSet(RESPONSE_HEADER_TAGS)) {
        logIngoredSettingWarning(RESPONSE_HEADER_TAGS, HEADER_TAGS, ".legacy.parsing.enabled");
      }
    } else {
      requestHeaderTags =
          configProvider.getMergedMapWithOptionalMappings(
              "http.request.headers.", true, HEADER_TAGS, REQUEST_HEADER_TAGS);
      responseHeaderTags =
          configProvider.getMergedMapWithOptionalMappings(
              "http.response.headers.", true, HEADER_TAGS, RESPONSE_HEADER_TAGS);
    }

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

    dbClientSplitByInstanceTypeSuffix =
        configProvider.getBoolean(
            DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX,
            DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX);

    splitByTags = tryMakeImmutableSet(configProvider.getList(SPLIT_BY_TAGS));

    scopeDepthLimit = configProvider.getInteger(SCOPE_DEPTH_LIMIT, DEFAULT_SCOPE_DEPTH_LIMIT);

    scopeStrictMode = configProvider.getBoolean(SCOPE_STRICT_MODE, false);

    scopeInheritAsyncPropagation = configProvider.getBoolean(SCOPE_INHERIT_ASYNC_PROPAGATION, true);

    scopeIterationKeepAlive =
        configProvider.getInteger(SCOPE_ITERATION_KEEP_ALIVE, DEFAULT_SCOPE_ITERATION_KEEP_ALIVE);

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

    clockSyncPeriod = configProvider.getInteger(CLOCK_SYNC_PERIOD, DEFAULT_CLOCK_SYNC_PERIOD);

    dogStatsDNamedPipe = configProvider.getString(DOGSTATSD_NAMED_PIPE);

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

    jmxFetchMultipleRuntimeServicesEnabled =
        configProvider.getBoolean(
            JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_ENABLED,
            DEFAULT_JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_ENABLED);
    jmxFetchMultipleRuntimeServicesLimit =
        configProvider.getInteger(
            JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_LIMIT,
            DEFAULT_JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_LIMIT);

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

    profilingEnabled = configProvider.getBoolean(PROFILING_ENABLED, PROFILING_ENABLED_DEFAULT);
    profilingAgentless =
        configProvider.getBoolean(PROFILING_AGENTLESS, PROFILING_AGENTLESS_DEFAULT);
    profilingLegacyTracingIntegrationEnabled =
        configProvider.getBoolean(
            PROFILING_LEGACY_TRACING_INTEGRATION, PROFILING_LEGACY_TRACING_INTEGRATION_DEFAULT);
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
        configProvider.getInteger(PROFILING_START_DELAY, PROFILING_START_DELAY_DEFAULT);
    profilingStartForceFirst =
        configProvider.getBoolean(PROFILING_START_FORCE_FIRST, PROFILING_START_FORCE_FIRST_DEFAULT);
    profilingUploadPeriod =
        configProvider.getInteger(PROFILING_UPLOAD_PERIOD, PROFILING_UPLOAD_PERIOD_DEFAULT);
    profilingTemplateOverrideFile = configProvider.getString(PROFILING_TEMPLATE_OVERRIDE_FILE);
    profilingUploadTimeout =
        configProvider.getInteger(PROFILING_UPLOAD_TIMEOUT, PROFILING_UPLOAD_TIMEOUT_DEFAULT);
    profilingUploadCompression =
        configProvider.getString(
            PROFILING_UPLOAD_COMPRESSION, PROFILING_UPLOAD_COMPRESSION_DEFAULT);
    profilingProxyHost = configProvider.getString(PROFILING_PROXY_HOST);
    profilingProxyPort =
        configProvider.getInteger(PROFILING_PROXY_PORT, PROFILING_PROXY_PORT_DEFAULT);
    profilingProxyUsername = configProvider.getString(PROFILING_PROXY_USERNAME);
    profilingProxyPassword = configProvider.getString(PROFILING_PROXY_PASSWORD);

    profilingExceptionSampleLimit =
        configProvider.getInteger(
            PROFILING_EXCEPTION_SAMPLE_LIMIT, PROFILING_EXCEPTION_SAMPLE_LIMIT_DEFAULT);
    profilingExceptionHistogramTopItems =
        configProvider.getInteger(
            PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS,
            PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS_DEFAULT);
    profilingExceptionHistogramMaxCollectionSize =
        configProvider.getInteger(
            PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE,
            PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE_DEFAULT);

    profilingExcludeAgentThreads = configProvider.getBoolean(PROFILING_EXCLUDE_AGENT_THREADS, true);

    // code hotspots are disabled by default because of potential perf overhead they can incur
    profilingHotspotsEnabled = configProvider.getBoolean(PROFILING_HOTSPOTS_ENABLED, false);

    profilingUploadSummaryOn413Enabled =
        configProvider.getBoolean(
            PROFILING_UPLOAD_SUMMARY_ON_413, PROFILING_UPLOAD_SUMMARY_ON_413_DEFAULT);

    appSecEnabled = configProvider.getBoolean(APPSEC_ENABLED, DEFAULT_APPSEC_ENABLED);
    appSecReportingInband =
        configProvider.getBoolean(APPSEC_REPORTING_INBAND, DEFAULT_APPSEC_REPORTING_INBAND);
    appSecRulesFile = configProvider.getString(APPSEC_RULES_FILE, null);

    // Default AppSec report timeout min=5, max=60
    appSecReportMaxTimeout = configProvider.getInteger(APPSEC_REPORT_TIMEOUT_SEC, 60);
    appSecReportMinTimeout = Math.min(appSecReportMaxTimeout, 5);
    String appSecIpAddrHeader = configProvider.getString(APPSEC_IP_ADDR_HEADER);
    if (appSecIpAddrHeader != null) {
      appSecIpAddrHeader = appSecIpAddrHeader.toLowerCase(Locale.ROOT);
    }
    this.appSecIpAddrHeader = appSecIpAddrHeader;

    appSecTraceRateLimit =
        configProvider.getInteger(APPSEC_TRACE_RATE_LIMIT, DEFAULT_APPSEC_TRACE_RATE_LIMIT);

    appSecWafMetrics = configProvider.getBoolean(APPSEC_WAF_METRICS, DEFAULT_APPSEC_WAF_METRICS);

    ciVisibilityEnabled =
        configProvider.getBoolean(CIVISIBILITY_ENABLED, DEFAULT_CIVISIBILITY_ENABLED);

    jdbcPreparedStatementClassName =
        configProvider.getString(JDBC_PREPARED_STATEMENT_CLASS_NAME, "");

    jdbcConnectionClassName = configProvider.getString(JDBC_CONNECTION_CLASS_NAME, "");

    awsPropagationEnabled = isPropagationEnabled(true, "aws");
    sqsPropagationEnabled = awsPropagationEnabled && isPropagationEnabled(true, "sqs");

    kafkaClientPropagationEnabled = isPropagationEnabled(true, "kafka", "kafka.client");
    kafkaClientPropagationDisabledTopics =
        tryMakeImmutableSet(configProvider.getList(KAFKA_CLIENT_PROPAGATION_DISABLED_TOPICS));
    kafkaClientBase64DecodingEnabled =
        configProvider.getBoolean(KAFKA_CLIENT_BASE64_DECODING_ENABLED, false);

    jmsPropagationEnabled = isPropagationEnabled(true, "jms");
    jmsPropagationDisabledTopics =
        tryMakeImmutableSet(configProvider.getList(JMS_PROPAGATION_DISABLED_TOPICS));
    jmsPropagationDisabledQueues =
        tryMakeImmutableSet(configProvider.getList(JMS_PROPAGATION_DISABLED_QUEUES));

    rabbitPropagationEnabled = isPropagationEnabled(true, "rabbit", "rabbitmq");
    rabbitPropagationDisabledQueues =
        tryMakeImmutableSet(configProvider.getList(RABBIT_PROPAGATION_DISABLED_QUEUES));
    rabbitPropagationDisabledExchanges =
        tryMakeImmutableSet(configProvider.getList(RABBIT_PROPAGATION_DISABLED_EXCHANGES));

    messageBrokerSplitByDestination =
        configProvider.getBoolean(MESSAGE_BROKER_SPLIT_BY_DESTINATION, false);

    grpcIgnoredInboundMethods =
        tryMakeImmutableSet(configProvider.getList(GRPC_IGNORED_INBOUND_METHODS));
    grpcIgnoredOutboundMethods =
        tryMakeImmutableSet(configProvider.getList(GRPC_IGNORED_OUTBOUND_METHODS));
    grpcServerTrimPackageResource =
        configProvider.getBoolean(GRPC_SERVER_TRIM_PACKAGE_RESOURCE, false);
    grpcServerErrorStatuses =
        configProvider.getIntegerRange(
            GRPC_SERVER_ERROR_STATUSES, DEFAULT_GRPC_SERVER_ERROR_STATUSES);
    grpcClientErrorStatuses =
        configProvider.getIntegerRange(
            GRPC_CLIENT_ERROR_STATUSES, DEFAULT_GRPC_CLIENT_ERROR_STATUSES);

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

    cwsEnabled = configProvider.getBoolean(CWS_ENABLED, DEFAULT_CWS_ENABLED);
    cwsTlsRefresh = configProvider.getInteger(CWS_TLS_REFRESH, DEFAULT_CWS_TLS_REFRESH);

    azureAppServices = configProvider.getBoolean(AZURE_APP_SERVICES, false);
    traceAgentPath = configProvider.getString(TRACE_AGENT_PATH);
    String traceAgentArgsString = configProvider.getString(TRACE_AGENT_ARGS);
    if (traceAgentArgsString == null) {
      traceAgentArgs = Collections.emptyList();
    } else {
      traceAgentArgs =
          Collections.unmodifiableList(
              new ArrayList<>(parseStringIntoSetOfNonEmptyStrings(traceAgentArgsString)));
    }

    dogStatsDPath = configProvider.getString(DOGSTATSD_PATH);
    String dogStatsDArgsString = configProvider.getString(DOGSTATSD_ARGS);
    if (dogStatsDArgsString == null) {
      dogStatsDArgs = Collections.emptyList();
    } else {
      dogStatsDArgs =
          Collections.unmodifiableList(
              new ArrayList<>(parseStringIntoSetOfNonEmptyStrings(dogStatsDArgsString)));
    }

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

  public String getAgentNamedPipe() {
    return agentNamedPipe;
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

  public Map<String, String> getRequestHeaderTags() {
    return requestHeaderTags;
  }

  public Map<String, String> getResponseHeaderTags() {
    return responseHeaderTags;
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

  public boolean isDbClientSplitByInstanceTypeSuffix() {
    return dbClientSplitByInstanceTypeSuffix;
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

  public int getScopeIterationKeepAlive() {
    return scopeIterationKeepAlive;
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

  public int getClockSyncPeriod() {
    return clockSyncPeriod;
  }

  public String getDogStatsDNamedPipe() {
    return dogStatsDNamedPipe;
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

  public boolean isJmxFetchMultipleRuntimeServicesEnabled() {
    return jmxFetchMultipleRuntimeServicesEnabled;
  }

  public int getJmxFetchMultipleRuntimeServicesLimit() {
    return jmxFetchMultipleRuntimeServicesLimit;
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

  public boolean isProfilingUploadSummaryOn413Enabled() {
    return profilingUploadSummaryOn413Enabled;
  }

  public boolean isProfilingLegacyTracingIntegrationEnabled() {
    return profilingLegacyTracingIntegrationEnabled;
  }

  public boolean isAppSecEnabled() {
    return appSecEnabled;
  }

  public boolean isAppSecReportingInband() {
    return appSecReportingInband;
  }

  public int getAppSecReportMinTimeout() {
    return appSecReportMinTimeout;
  }

  public String getAppSecIpAddrHeader() {
    return appSecIpAddrHeader;
  }

  public int getAppSecReportMaxTimeout() {
    return appSecReportMaxTimeout;
  }

  public int getAppSecTraceRateLimit() {
    return appSecTraceRateLimit;
  }

  public boolean isAppSecWafMetrics() {
    return appSecWafMetrics;
  }

  public boolean isCiVisibilityEnabled() {
    return ciVisibilityEnabled;
  }

  public String getAppSecRulesFile() {
    return appSecRulesFile;
  }

  public boolean isAwsPropagationEnabled() {
    return awsPropagationEnabled;
  }

  public boolean isSqsPropagationEnabled() {
    return sqsPropagationEnabled;
  }

  public boolean isKafkaClientPropagationEnabled() {
    return kafkaClientPropagationEnabled;
  }

  public boolean isKafkaClientPropagationDisabledForTopic(String topic) {
    return null != topic && kafkaClientPropagationDisabledTopics.contains(topic);
  }

  public boolean isJmsPropagationEnabled() {
    return jmsPropagationEnabled;
  }

  public boolean isJmsPropagationDisabledForDestination(final String queueOrTopic) {
    return null != queueOrTopic
        && (jmsPropagationDisabledQueues.contains(queueOrTopic)
            || jmsPropagationDisabledTopics.contains(queueOrTopic));
  }

  public boolean isKafkaClientBase64DecodingEnabled() {
    return kafkaClientBase64DecodingEnabled;
  }

  public boolean isRabbitPropagationEnabled() {
    return rabbitPropagationEnabled;
  }

  public boolean isRabbitPropagationDisabledForDestination(final String queueOrExchange) {
    return null != queueOrExchange
        && (rabbitPropagationDisabledQueues.contains(queueOrExchange)
            || rabbitPropagationDisabledExchanges.contains(queueOrExchange));
  }

  public boolean isMessageBrokerSplitByDestination() {
    return messageBrokerSplitByDestination;
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

  public boolean isCwsEnabled() {
    return cwsEnabled;
  }

  public int getCwsTlsRefresh() {
    return cwsTlsRefresh;
  }

  public boolean isAzureAppServices() {
    return azureAppServices;
  }

  public String getTraceAgentPath() {
    return traceAgentPath;
  }

  public List<String> getTraceAgentArgs() {
    return traceAgentArgs;
  }

  public String getDogStatsDPath() {
    return dogStatsDPath;
  }

  public List<String> getDogStatsDArgs() {
    return dogStatsDArgs;
  }

  public String getConfigFileStatus() {
    return configFileStatus;
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

  public Set<String> getGrpcIgnoredInboundMethods() {
    return grpcIgnoredInboundMethods;
  }

  public Set<String> getGrpcIgnoredOutboundMethods() {
    return grpcIgnoredOutboundMethods;
  }

  public boolean isGrpcServerTrimPackageResource() {
    return grpcServerTrimPackageResource;
  }

  public BitSet getGrpcServerErrorStatuses() {
    return grpcServerErrorStatuses;
  }

  public BitSet getGrpcClientErrorStatuses() {
    return grpcClientErrorStatuses;
  }

  /** @return A map of tags to be applied only to the local application root span. */
  public Map<String, Object> getLocalRootSpanTags() {
    final Map<String, String> runtimeTags = getRuntimeTags();
    final Map<String, Object> result = new HashMap<>(runtimeTags.size() + 1);
    result.putAll(runtimeTags);
    result.put(LANGUAGE_TAG_KEY, LANGUAGE_TAG_VALUE);

    if (reportHostName) {
      final String hostName = getHostName();
      if (null != hostName && !hostName.isEmpty()) {
        result.put(INTERNAL_HOST_NAME, hostName);
      }
    }

    if (azureAppServices) {
      result.putAll(getAzureAppServicesTags());
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
  public Map<String, String> getGlobalTags() {
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
    return Collections.singletonMap(RUNTIME_ID_TAG, runtimeId);
  }

  private Map<String, String> getAzureAppServicesTags() {
    // These variable names and derivations are copied from the dotnet tracer
    // See
    // https://github.com/DataDog/dd-trace-dotnet/blob/master/tracer/src/Datadog.Trace/PlatformHelpers/AzureAppServices.cs
    // and
    // https://github.com/DataDog/dd-trace-dotnet/blob/master/tracer/src/Datadog.Trace/TraceContext.cs#L207
    Map<String, String> aasTags = new HashMap<>();

    /// The site name of the site instance in Azure where the traced application is running.
    String siteName = System.getenv("WEBSITE_SITE_NAME");
    if (siteName != null) {
      aasTags.put("aas.site.name", siteName);
    }

    // The kind of application instance running in Azure.
    // Possible values: app, api, mobileapp, app_linux, app_linux_container, functionapp,
    // functionapp_linux, functionapp_linux_container

    // The type of application instance running in Azure.
    // Possible values: app, function
    if (System.getenv("FUNCTIONS_WORKER_RUNTIME") != null
        || System.getenv("FUNCTIONS_EXTENSIONS_VERSION") != null) {
      aasTags.put("aas.site.kind", "functionapp");
      aasTags.put("aas.site.type", "function");
    } else {
      aasTags.put("aas.site.kind", "app");
      aasTags.put("aas.site.type", "app");
    }

    //  The resource group of the site instance in Azure App Services
    String resourceGroup = System.getenv("WEBSITE_RESOURCE_GROUP");
    if (resourceGroup != null) {
      aasTags.put("aas.resource.group", resourceGroup);
    }

    // Example: 8c500027-5f00-400e-8f00-60000000000f+apm-dotnet-EastUSwebspace
    // Format: {subscriptionId}+{planResourceGroup}-{hostedInRegion}
    String websiteOwner = System.getenv("WEBSITE_OWNER_NAME");
    int plusIndex = websiteOwner == null ? -1 : websiteOwner.indexOf("+");

    // The subscription ID of the site instance in Azure App Services
    String subscriptionId = null;
    if (plusIndex > 0) {
      subscriptionId = websiteOwner.substring(0, plusIndex);
      aasTags.put("aas.subscription.id", subscriptionId);
    }

    if (subscriptionId != null && siteName != null && resourceGroup != null) {
      // The resource ID of the site instance in Azure App Services
      String resourceId =
          "/subscriptions/"
              + subscriptionId
              + "/resourcegroups/"
              + resourceGroup
              + "/providers/microsoft.web/sites/"
              + siteName;
      resourceId = resourceId.toLowerCase();
      aasTags.put("aas.resource.id", resourceId);
    } else {
      log.warn(
          "Unable to generate resource id subscription id: {}, site name: {}, resource group {}",
          subscriptionId,
          siteName,
          resourceGroup);
    }

    // The instance ID in Azure
    String instanceId = System.getenv("WEBSITE_INSTANCE_ID");
    instanceId = instanceId == null ? "unknown" : instanceId;
    aasTags.put("aas.environment.instance_id", instanceId);

    // The instance name in Azure
    String instanceName = System.getenv("COMPUTERNAME");
    instanceName = instanceName == null ? "unknown" : instanceName;
    aasTags.put("aas.environment.instance_name", instanceName);

    // The operating system in Azure
    String operatingSystem = System.getenv("WEBSITE_OS");
    operatingSystem = operatingSystem == null ? "unknown" : operatingSystem;
    aasTags.put("aas.environment.os", operatingSystem);

    // The version of the extension installed
    String siteExtensionVersion = System.getenv("DD_AAS_JAVA_EXTENSION_VERSION");
    siteExtensionVersion = siteExtensionVersion == null ? "unknown" : siteExtensionVersion;
    aasTags.put("aas.environment.extension_version", siteExtensionVersion);

    aasTags.put("aas.environment.runtime", System.getProperty("java.vm.name", "unknown"));

    return aasTags;
  }

  public String getFinalProfilingUrl() {
    if (profilingUrl != null) {
      // when profilingUrl is set we use it regardless of apiKey/agentless config
      return profilingUrl;
    } else if (profilingAgentless) {
      // when agentless profiling is turned on we send directly to our intake
      if (configProvider.getBoolean(
          PROFILING_FORMAT_V2_4_ENABLED, PROFILING_FORMAT_V2_4_ENABLED_DEFAULT)) {
        return "https://intake.profile." + site + "/api/v2/profile";
      }
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

  public boolean isIntegrationShortcutMatchingEnabled(
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

  public boolean isPropagationEnabled(
      final boolean defaultEnabled, final String... integrationNames) {
    return isEnabled(Arrays.asList(integrationNames), "", ".propagation.enabled", defaultEnabled);
  }

  public boolean isLegacyTracingEnabled(
      final boolean defaultEnabled, final String... integrationNames) {
    return isEnabled(
        Arrays.asList(integrationNames), "", ".legacy.tracing.enabled", defaultEnabled);
  }

  public boolean isEnabled(
      final boolean defaultEnabled, final String settingName, String settingSuffix) {
    return isEnabled(Collections.singletonList(settingName), "", settingSuffix, defaultEnabled);
  }

  private void logIngoredSettingWarning(
      String setting, String overridingSetting, String overridingSuffix) {
    log.warn(
        "Setting {} ignored since {}{} is enabled.",
        propertyNameToSystemPropertyName(setting),
        propertyNameToSystemPropertyName(overridingSetting),
        overridingSuffix);
  }

  public boolean isTraceAnalyticsIntegrationEnabled(
      final SortedSet<String> integrationNames, final boolean defaultEnabled) {
    return isEnabled(integrationNames, "", ".analytics.enabled", defaultEnabled);
  }

  public boolean isTraceAnalyticsIntegrationEnabled(
      final boolean defaultEnabled, final String... integrationNames) {
    return isEnabled(Arrays.asList(integrationNames), "", ".analytics.enabled", defaultEnabled);
  }

  public boolean isSamplingMechanismValidationDisabled() {
    return configProvider.getBoolean(TracerConfig.SAMPLING_MECHANISM_VALIDATION_DISABLED, false);
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
      final String fullKey = configKey.startsWith("trace.") ? configKey : "trace." + configKey;
      final boolean configEnabled = configProvider.getBoolean(fullKey, defaultEnabled, configKey);
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
      return new Config(ConfigProvider.withPropertiesOverride(properties));
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
        + ", requestHeaderTags="
        + requestHeaderTags
        + ", responseHeaderTags="
        + responseHeaderTags
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
        + ", dbClientSplitByInstanceTypeSuffix="
        + dbClientSplitByInstanceTypeSuffix
        + ", splitByTags="
        + splitByTags
        + ", scopeDepthLimit="
        + scopeDepthLimit
        + ", scopeStrictMode="
        + scopeStrictMode
        + ", scopeInheritAsyncPropagation="
        + scopeInheritAsyncPropagation
        + ", scopeIterationKeepAlive="
        + scopeIterationKeepAlive
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
        + ", clockSyncPeriod="
        + clockSyncPeriod
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
        + ", awsPropagationEnabled="
        + awsPropagationEnabled
        + ", sqsPropagationEnabled="
        + sqsPropagationEnabled
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
        + ", rabbitPropagationEnabled="
        + rabbitPropagationEnabled
        + ", rabbitPropagationDisabledQueues="
        + rabbitPropagationDisabledQueues
        + ", rabbitPropagationDisabledExchanges="
        + rabbitPropagationDisabledExchanges
        + ", messageBrokerSplitByDestination="
        + messageBrokerSplitByDestination
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
        + configFileStatus
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
        + ", grpcIgnoredInboundMethods="
        + grpcIgnoredInboundMethods
        + ", grpcIgnoredOutboundMethods="
        + grpcIgnoredOutboundMethods
        + ", grpcServerErrorStatuses="
        + grpcServerErrorStatuses
        + ", grpcClientErrorStatuses="
        + grpcClientErrorStatuses
        + ", configProvider="
        + configProvider
        + ", appSecEnabled="
        + appSecEnabled
        + ", appSecReportingInband="
        + appSecReportingInband
        + ", appSecRulesFile='"
        + appSecRulesFile
        + "'"
        + ", cwsEnabled="
        + cwsEnabled
        + ", cwsTlsRefresh="
        + cwsTlsRefresh
        + '}';
  }
}
