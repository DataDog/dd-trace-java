package datadog.trace.api;

import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_HOST;
import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_TIMEOUT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_UNIX_DOMAIN_SOCKET;
import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_WRITER_TYPE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_ANALYTICS_SAMPLE_RATE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HEALTH_METRICS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_CLIENT_ERROR_STATUSES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_CLIENT_SPLIT_BY_DOMAIN;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_CLIENT_TAG_QUERY_STRING;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_SERVER_ERROR_STATUSES;
import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_SERVER_TAG_QUERY_STRING;
import static datadog.trace.api.ConfigDefaults.DEFAULT_INTEGRATIONS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_JMX_FETCH_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_JMX_FETCH_STATSD_PORT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_KAFKA_CLIENT_PROPAGATION_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_LOGS_INJECTION_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PARTIAL_FLUSH_MIN_SPANS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PERF_METRICS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PRIORITIZATION_TYPE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PRIORITY_SAMPLING_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PRIORITY_SAMPLING_FORCE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROFILING_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROFILING_EXCEPTION_SAMPLE_LIMIT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROFILING_PROXY_PORT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROFILING_START_DELAY;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROFILING_START_FORCE_FIRST;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROFILING_UPLOAD_COMPRESSION;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROFILING_UPLOAD_PERIOD;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROFILING_UPLOAD_TIMEOUT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROPAGATION_STYLE_EXTRACT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PROPAGATION_STYLE_INJECT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_RUNTIME_CONTEXT_FIELD_INJECTION;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SCOPE_DEPTH_LIMIT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SERVICE_NAME;
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
import static datadog.trace.api.config.GeneralConfig.RUNTIME_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.TRACER_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.TRACER_METRICS_MAX_AGGREGATES;
import static datadog.trace.api.config.GeneralConfig.TRACER_METRICS_MAX_PENDING;
import static datadog.trace.api.config.TraceInstrumentationConfig.HYSTRIX_TAGS_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.LOGS_MDC_TAGS_INJECTION_ENABLED;
import static datadog.trace.api.config.TracerConfig.ENABLE_TRACE_AGENT_V05;

import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.config.JmxFetchConfig;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.config.TraceInstrumentationConfig;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
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
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
@ToString(includeFieldNames = true)
public class Config {

  /** Config keys below */
  public static final String CONFIGURATION_FILE = GeneralConfig.CONFIGURATION_FILE;

  public static final String API_KEY = GeneralConfig.API_KEY;
  public static final String API_KEY_FILE = GeneralConfig.API_KEY_FILE;
  public static final String SITE = GeneralConfig.SITE;
  public static final String SERVICE_NAME = GeneralConfig.SERVICE_NAME;
  public static final String TRACE_ENABLED = TraceInstrumentationConfig.TRACE_ENABLED;
  public static final String INTEGRATIONS_ENABLED = TraceInstrumentationConfig.INTEGRATIONS_ENABLED;
  public static final String ID_GENERATION_STRATEGY = TracerConfig.ID_GENERATION_STRATEGY;
  public static final String WRITER_TYPE = TracerConfig.WRITER_TYPE;
  public static final String PRIORITIZATION_TYPE = TracerConfig.PRIORITIZATION_TYPE;
  public static final String TRACE_AGENT_URL = TracerConfig.TRACE_AGENT_URL;
  public static final String AGENT_HOST = TracerConfig.AGENT_HOST;
  public static final String TRACE_AGENT_PORT = TracerConfig.TRACE_AGENT_PORT;
  public static final String AGENT_PORT_LEGACY = TracerConfig.AGENT_PORT_LEGACY;
  public static final String AGENT_UNIX_DOMAIN_SOCKET = TracerConfig.AGENT_UNIX_DOMAIN_SOCKET;
  public static final String AGENT_TIMEOUT = TracerConfig.AGENT_TIMEOUT;
  public static final String PRIORITY_SAMPLING = TracerConfig.PRIORITY_SAMPLING;
  public static final String PRIORITY_SAMPLING_FORCE = TracerConfig.PRIORITY_SAMPLING_FORCE;

  @Deprecated
  public static final String TRACE_RESOLVER_ENABLED = TracerConfig.TRACE_RESOLVER_ENABLED;

  public static final String SERVICE_MAPPING = TracerConfig.SERVICE_MAPPING;

  private static final String ENV = GeneralConfig.ENV;
  private static final String VERSION = GeneralConfig.VERSION;
  public static final String TAGS = GeneralConfig.TAGS;
  @Deprecated // Use dd.tags instead
  public static final String GLOBAL_TAGS = GeneralConfig.GLOBAL_TAGS;
  public static final String SPAN_TAGS = TracerConfig.SPAN_TAGS;
  public static final String JMX_TAGS = JmxFetchConfig.JMX_TAGS;
  public static final String TRACE_ANALYTICS_ENABLED = TracerConfig.TRACE_ANALYTICS_ENABLED;
  public static final String TRACE_ANNOTATIONS = TraceInstrumentationConfig.TRACE_ANNOTATIONS;
  public static final String TRACE_EXECUTORS_ALL = TraceInstrumentationConfig.TRACE_EXECUTORS_ALL;
  public static final String TRACE_EXECUTORS = TraceInstrumentationConfig.TRACE_EXECUTORS;
  public static final String TRACE_METHODS = TraceInstrumentationConfig.TRACE_METHODS;
  public static final String TRACE_CLASSES_EXCLUDE =
      TraceInstrumentationConfig.TRACE_CLASSES_EXCLUDE;
  public static final String TRACE_SAMPLING_SERVICE_RULES =
      TracerConfig.TRACE_SAMPLING_SERVICE_RULES;
  public static final String TRACE_SAMPLING_OPERATION_RULES =
      TracerConfig.TRACE_SAMPLING_OPERATION_RULES;
  public static final String TRACE_SAMPLE_RATE = TracerConfig.TRACE_SAMPLE_RATE;
  public static final String TRACE_RATE_LIMIT = TracerConfig.TRACE_RATE_LIMIT;
  public static final String TRACE_REPORT_HOSTNAME = TracerConfig.TRACE_REPORT_HOSTNAME;
  public static final String HEADER_TAGS = TracerConfig.HEADER_TAGS;
  public static final String HTTP_SERVER_ERROR_STATUSES = TracerConfig.HTTP_SERVER_ERROR_STATUSES;
  public static final String HTTP_CLIENT_ERROR_STATUSES = TracerConfig.HTTP_CLIENT_ERROR_STATUSES;
  public static final String HTTP_SERVER_TAG_QUERY_STRING =
      TraceInstrumentationConfig.HTTP_SERVER_TAG_QUERY_STRING;
  public static final String HTTP_CLIENT_TAG_QUERY_STRING =
      TraceInstrumentationConfig.HTTP_CLIENT_TAG_QUERY_STRING;
  public static final String HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN =
      TraceInstrumentationConfig.HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN;
  public static final String DB_CLIENT_HOST_SPLIT_BY_INSTANCE =
      TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE;
  public static final String SPLIT_BY_TAGS = TracerConfig.SPLIT_BY_TAGS;
  public static final String SCOPE_DEPTH_LIMIT = TracerConfig.SCOPE_DEPTH_LIMIT;
  public static final String SCOPE_STRICT_MODE = TracerConfig.SCOPE_STRICT_MODE;
  public static final String SCOPE_INHERIT_ASYNC_PROPAGATION =
      TracerConfig.SCOPE_INHERIT_ASYNC_PROPAGATION;
  public static final String PARTIAL_FLUSH_MIN_SPANS = TracerConfig.PARTIAL_FLUSH_MIN_SPANS;
  public static final String RUNTIME_CONTEXT_FIELD_INJECTION =
      TraceInstrumentationConfig.RUNTIME_CONTEXT_FIELD_INJECTION;
  public static final String PROPAGATION_STYLE_EXTRACT = TracerConfig.PROPAGATION_STYLE_EXTRACT;
  public static final String PROPAGATION_STYLE_INJECT = TracerConfig.PROPAGATION_STYLE_INJECT;

  public static final String JMX_FETCH_ENABLED = JmxFetchConfig.JMX_FETCH_ENABLED;
  public static final String JMX_FETCH_CONFIG_DIR = JmxFetchConfig.JMX_FETCH_CONFIG_DIR;
  public static final String JMX_FETCH_CONFIG = JmxFetchConfig.JMX_FETCH_CONFIG;

  @Deprecated
  public static final String JMX_FETCH_METRICS_CONFIGS = JmxFetchConfig.JMX_FETCH_METRICS_CONFIGS;

  public static final String JMX_FETCH_CHECK_PERIOD = JmxFetchConfig.JMX_FETCH_CHECK_PERIOD;
  public static final String JMX_FETCH_REFRESH_BEANS_PERIOD =
      JmxFetchConfig.JMX_FETCH_REFRESH_BEANS_PERIOD;
  public static final String JMX_FETCH_STATSD_HOST = JmxFetchConfig.JMX_FETCH_STATSD_HOST;
  public static final String JMX_FETCH_STATSD_PORT = JmxFetchConfig.JMX_FETCH_STATSD_PORT;

  public static final String HEALTH_METRICS_ENABLED = GeneralConfig.HEALTH_METRICS_ENABLED;
  public static final String HEALTH_METRICS_STATSD_HOST = GeneralConfig.HEALTH_METRICS_STATSD_HOST;
  public static final String HEALTH_METRICS_STATSD_PORT = GeneralConfig.HEALTH_METRICS_STATSD_PORT;
  public static final String PERF_METRICS_ENABLED = GeneralConfig.PERF_METRICS_ENABLED;

  public static final String LOGS_INJECTION_ENABLED =
      TraceInstrumentationConfig.LOGS_INJECTION_ENABLED;

  public static final String PROFILING_ENABLED = ProfilingConfig.PROFILING_ENABLED;
  @Deprecated // Use dd.site instead
  public static final String PROFILING_URL = ProfilingConfig.PROFILING_URL;
  @Deprecated // Use dd.api-key instead
  public static final String PROFILING_API_KEY_OLD = ProfilingConfig.PROFILING_API_KEY_OLD;
  @Deprecated // Use dd.api-key-file instead
  public static final String PROFILING_API_KEY_FILE_OLD =
      ProfilingConfig.PROFILING_API_KEY_FILE_OLD;
  @Deprecated // Use dd.api-key instead
  public static final String PROFILING_API_KEY_VERY_OLD =
      ProfilingConfig.PROFILING_API_KEY_VERY_OLD;
  @Deprecated // Use dd.api-key-file instead
  public static final String PROFILING_API_KEY_FILE_VERY_OLD =
      ProfilingConfig.PROFILING_API_KEY_FILE_VERY_OLD;
  public static final String PROFILING_TAGS = ProfilingConfig.PROFILING_TAGS;
  public static final String PROFILING_START_DELAY = ProfilingConfig.PROFILING_START_DELAY;
  // DANGEROUS! May lead on sigsegv on JVMs before 14
  // Not intended for production use
  public static final String PROFILING_START_FORCE_FIRST =
      ProfilingConfig.PROFILING_START_FORCE_FIRST;
  public static final String PROFILING_UPLOAD_PERIOD = ProfilingConfig.PROFILING_UPLOAD_PERIOD;
  public static final String PROFILING_TEMPLATE_OVERRIDE_FILE =
      ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE;
  public static final String PROFILING_UPLOAD_TIMEOUT = ProfilingConfig.PROFILING_UPLOAD_TIMEOUT;
  public static final String PROFILING_UPLOAD_COMPRESSION =
      ProfilingConfig.PROFILING_UPLOAD_COMPRESSION;
  public static final String PROFILING_PROXY_HOST = ProfilingConfig.PROFILING_PROXY_HOST;
  public static final String PROFILING_PROXY_PORT = ProfilingConfig.PROFILING_PROXY_PORT;
  public static final String PROFILING_PROXY_USERNAME = ProfilingConfig.PROFILING_PROXY_USERNAME;
  public static final String PROFILING_PROXY_PASSWORD = ProfilingConfig.PROFILING_PROXY_PASSWORD;
  public static final String PROFILING_EXCEPTION_SAMPLE_LIMIT =
      ProfilingConfig.PROFILING_EXCEPTION_SAMPLE_LIMIT;
  public static final String PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS =
      ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS;
  public static final String PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE =
      ProfilingConfig.PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE;
  public static final String PROFILING_EXCLUDE_AGENT_THREADS =
      ProfilingConfig.PROFILING_EXCLUDE_AGENT_THREADS;

  public static final String KAFKA_CLIENT_PROPAGATION_ENABLED =
      TraceInstrumentationConfig.KAFKA_CLIENT_PROPAGATION_ENABLED;

  public static final String KAFKA_CLIENT_BASE64_DECODING_ENABLED =
      TraceInstrumentationConfig.KAFKA_CLIENT_BASE64_DECODING_ENABLED;

  private static final String TRACE_AGENT_URL_TEMPLATE = "http://%s:%d";

  private static final String PROFILING_REMOTE_URL_TEMPLATE = "https://intake.profile.%s/v1/input";
  private static final String PROFILING_LOCAL_URL_TEMPLATE = "http://%s:%d/profiling/v1/input";

  private static final Pattern ENV_REPLACEMENT = Pattern.compile("[^a-zA-Z0-9_]");
  private static final String SPLIT_BY_SPACE_OR_COMMA_REGEX = "[,\\s]+";

  /** Used for masking sensitive information when doing toString */
  @ToString.Include(name = "apiKey")
  private String profilingApiKeyMasker() {
    return apiKey != null ? "****" : null;
  }

  /** Used for masking sensitive information when doing toString */
  @ToString.Include(name = "profilingProxyPassword")
  private String profilingProxyPasswordMasker() {
    return profilingProxyPassword != null ? "****" : null;
  }

  /**
   * this is a random UUID that gets generated on JVM start up and is attached to every root span
   * and every JMX metric that is sent out.
   */
  @Getter private final String runtimeId;

  /**
   * Note: this has effect only on profiling site. Traces are sent to Datadog agent and are not
   * affected by this setting.
   */
  @Getter private final String apiKey;
  /**
   * Note: this has effect only on profiling site. Traces are sent to Datadog agent and are not
   * affected by this setting.
   */
  @Getter private final String site;

  @Getter private final String serviceName;
  @Getter private final boolean traceEnabled;
  @Getter private final boolean integrationsEnabled;
  @Getter private final String writerType;
  @Getter private final String prioritizationType;
  @Getter private final boolean agentConfiguredUsingDefault;
  @Getter private final String agentUrl;
  @Getter private final String agentHost;
  @Getter private final int agentPort;
  @Getter private final String agentUnixDomainSocket;
  @Getter private final int agentTimeout;
  @Getter private final boolean prioritySamplingEnabled;
  @Getter private final String prioritySamplingForce;
  @Getter private final boolean traceResolverEnabled;
  @Getter private final Map<String, String> serviceMapping;
  @NonNull private final Map<String, String> tags;
  private final Map<String, String> spanTags;
  private final Map<String, String> jmxTags;
  @Getter private final List<String> excludedClasses;
  @Getter private final Map<String, String> headerTags;
  @Getter private final BitSet httpServerErrorStatuses;
  @Getter private final BitSet httpClientErrorStatuses;
  @Getter private final boolean httpServerTagQueryString;
  @Getter private final boolean httpClientTagQueryString;
  @Getter private final boolean httpClientSplitByDomain;
  @Getter private final boolean dbClientSplitByInstance;
  @Getter private final Set<String> splitByTags;
  @Getter private final int scopeDepthLimit;
  @Getter private final boolean scopeStrictMode;
  @Getter private final boolean scopeInheritAsyncPropagation;
  @Getter private final int partialFlushMinSpans;
  @Getter private final boolean runtimeContextFieldInjection;
  @Getter private final Set<PropagationStyle> propagationStylesToExtract;
  @Getter private final Set<PropagationStyle> propagationStylesToInject;

  @Getter private final boolean jmxFetchEnabled;
  @Getter private final String jmxFetchConfigDir;
  @Getter private final List<String> jmxFetchConfigs;
  @Deprecated @Getter private final List<String> jmxFetchMetricsConfigs;
  @Getter private final Integer jmxFetchCheckPeriod;
  @Getter private final Integer jmxFetchRefreshBeansPeriod;
  @Getter private final String jmxFetchStatsdHost;
  @Getter private final Integer jmxFetchStatsdPort;

  // These values are default-ed to those of jmx fetch values as needed
  @Getter private final boolean healthMetricsEnabled;
  @Getter private final String healthMetricsStatsdHost;
  @Getter private final Integer healthMetricsStatsdPort;
  @Getter private final boolean perfMetricsEnabled;

  @Getter private final boolean tracerMetricsEnabled;
  @Getter private final int tracerMetricsMaxAggregates;
  @Getter private final int tracerMetricsMaxPending;

  @Getter private final boolean logsInjectionEnabled;
  @Getter private final boolean logsMDCTagsInjectionEnabled;
  @Getter private final boolean reportHostName;

  @Getter private final String traceAnnotations;

  @Getter private final String traceMethods;

  @Getter private final boolean traceExecutorsAll;
  @Getter private final List<String> traceExecutors;

  @Getter private final boolean traceAnalyticsEnabled;

  @Getter private final Map<String, String> traceSamplingServiceRules;
  @Getter private final Map<String, String> traceSamplingOperationRules;
  @Getter private final Double traceSampleRate;
  @Getter private final int traceRateLimit;

  @Getter private final boolean profilingEnabled;
  @Deprecated private final String profilingUrl;
  private final Map<String, String> profilingTags;
  @Getter private final int profilingStartDelay;
  @Getter private final boolean profilingStartForceFirst;
  @Getter private final int profilingUploadPeriod;
  @Getter private final String profilingTemplateOverrideFile;
  @Getter private final int profilingUploadTimeout;
  @Getter private final String profilingUploadCompression;
  @Getter private final String profilingProxyHost;
  @Getter private final int profilingProxyPort;
  @Getter private final String profilingProxyUsername;
  @Getter private final String profilingProxyPassword;
  @Getter private final int profilingExceptionSampleLimit;
  @Getter private final int profilingExceptionHistogramTopItems;
  @Getter private final int profilingExceptionHistogramMaxCollectionSize;
  @Getter private final boolean profilingExcludeAgentThreads;

  @Getter private final boolean kafkaClientPropagationEnabled;
  @Getter private final boolean kafkaClientBase64DecodingEnabled;

  @Getter private final boolean hystrixTagsEnabled;
  @Getter private final boolean servletPrincipalEnabled;
  @Getter private final boolean servletAsyncTimeoutError;

  @Getter private final boolean traceAgentV05Enabled;

  @Getter private final boolean debugEnabled;
  @Getter private final String configFile;

  @Getter private final IdGenerationStrategy idGenerationStrategy;

  @Getter private final boolean internalExitOnFailure;

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
    String tmpApiKey = configProvider.getStringBypassSysProps(API_KEY, null);
    if (apiKeyFile != null) {
      try {
        tmpApiKey =
            new String(Files.readAllBytes(Paths.get(apiKeyFile)), StandardCharsets.UTF_8).trim();
      } catch (final IOException e) {
        log.error("Cannot read API key from file {}, skipping", apiKeyFile, e);
      }
    }
    site = configProvider.getString(SITE, DEFAULT_SITE);
    serviceName = configProvider.getString(SERVICE, DEFAULT_SERVICE_NAME, SERVICE_NAME);

    traceEnabled = configProvider.getBoolean(TRACE_ENABLED, DEFAULT_TRACE_ENABLED);
    integrationsEnabled =
        configProvider.getBoolean(INTEGRATIONS_ENABLED, DEFAULT_INTEGRATIONS_ENABLED);
    writerType = configProvider.getString(WRITER_TYPE, DEFAULT_AGENT_WRITER_TYPE);
    prioritizationType = configProvider.getString(PRIORITIZATION_TYPE, DEFAULT_PRIORITIZATION_TYPE);

    idGenerationStrategy =
        configProvider.getEnum(ID_GENERATION_STRATEGY, IdGenerationStrategy.class, RANDOM);
    if (idGenerationStrategy != RANDOM) {
      log.warn(
          "*** you are using an unsupported id generation strategy {} - this can impact correctness of traces",
          idGenerationStrategy);
    }

    String agentHostFromEnvironment = null;
    int agentPortFromEnvironment = -1;
    String unixDomainFromEnvironment = null;
    boolean rebuildAgentUrl = false;

    final String agentUrlFromEnvironment = configProvider.getString(TRACE_AGENT_URL);
    if (agentUrlFromEnvironment != null) {
      try {
        final URI parsedAgentUrl = new URI(agentUrlFromEnvironment);
        agentHostFromEnvironment = parsedAgentUrl.getHost();
        agentPortFromEnvironment = parsedAgentUrl.getPort();
        if ("unix".equals(parsedAgentUrl.getScheme())) {
          unixDomainFromEnvironment = parsedAgentUrl.getPath();
        }
      } catch (URISyntaxException e) {
        log.warn("{} not configured correctly: {}. Ignoring", TRACE_AGENT_URL, e.getMessage());
      }
    }

    if (agentHostFromEnvironment == null) {
      agentHostFromEnvironment = configProvider.getString(AGENT_HOST);
      rebuildAgentUrl = true;
    }

    // The extra code is to detect when defaults are used for agent configuration
    final boolean agentHostConfiguredUsingDefault;
    if (agentHostFromEnvironment == null) {
      agentHost = DEFAULT_AGENT_HOST;
      agentHostConfiguredUsingDefault = true;
    } else {
      agentHost = agentHostFromEnvironment;
      agentHostConfiguredUsingDefault = false;
    }

    if (agentPortFromEnvironment < 0) {
      agentPort =
          configProvider.getInteger(TRACE_AGENT_PORT, DEFAULT_TRACE_AGENT_PORT, AGENT_PORT_LEGACY);
      rebuildAgentUrl = true;
    } else {
      agentPort = agentPortFromEnvironment;
    }

    if (rebuildAgentUrl) {
      agentUrl = String.format(TRACE_AGENT_URL_TEMPLATE, agentHost, agentPort);
    } else {
      agentUrl = agentUrlFromEnvironment;
    }

    if (unixDomainFromEnvironment == null) {
      unixDomainFromEnvironment = configProvider.getString(AGENT_UNIX_DOMAIN_SOCKET);
    }

    final boolean socketConfiguredUsingDefault;
    if (unixDomainFromEnvironment == null) {
      agentUnixDomainSocket = DEFAULT_AGENT_UNIX_DOMAIN_SOCKET;
      socketConfiguredUsingDefault = true;
    } else {
      agentUnixDomainSocket = unixDomainFromEnvironment;
      socketConfiguredUsingDefault = false;
    }

    agentConfiguredUsingDefault =
        agentHostConfiguredUsingDefault
            && socketConfiguredUsingDefault
            && agentPort == DEFAULT_TRACE_AGENT_PORT;

    agentTimeout = configProvider.getInteger(AGENT_TIMEOUT, DEFAULT_AGENT_TIMEOUT);
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

    excludedClasses = configProvider.getList(TRACE_CLASSES_EXCLUDE);
    headerTags = configProvider.getMergedMap(HEADER_TAGS);

    httpServerErrorStatuses =
        configProvider.getIntegerRange(
            HTTP_SERVER_ERROR_STATUSES, DEFAULT_HTTP_SERVER_ERROR_STATUSES);

    httpClientErrorStatuses =
        configProvider.getIntegerRange(
            HTTP_CLIENT_ERROR_STATUSES, DEFAULT_HTTP_CLIENT_ERROR_STATUSES);

    httpServerTagQueryString =
        configProvider.getBoolean(
            HTTP_SERVER_TAG_QUERY_STRING, DEFAULT_HTTP_SERVER_TAG_QUERY_STRING);

    httpClientTagQueryString =
        configProvider.getBoolean(
            HTTP_CLIENT_TAG_QUERY_STRING, DEFAULT_HTTP_CLIENT_TAG_QUERY_STRING);

    httpClientSplitByDomain =
        configProvider.getBoolean(
            HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, DEFAULT_HTTP_CLIENT_SPLIT_BY_DOMAIN);

    dbClientSplitByInstance =
        configProvider.getBoolean(
            DB_CLIENT_HOST_SPLIT_BY_INSTANCE, DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE);

    splitByTags =
        Collections.unmodifiableSet(new LinkedHashSet<>(configProvider.getList(SPLIT_BY_TAGS)));

    scopeDepthLimit = configProvider.getInteger(SCOPE_DEPTH_LIMIT, DEFAULT_SCOPE_DEPTH_LIMIT);

    scopeStrictMode = configProvider.getBoolean(SCOPE_STRICT_MODE, false);

    scopeInheritAsyncPropagation = configProvider.getBoolean(SCOPE_INHERIT_ASYNC_PROPAGATION, true);

    partialFlushMinSpans =
        configProvider.getInteger(PARTIAL_FLUSH_MIN_SPANS, DEFAULT_PARTIAL_FLUSH_MIN_SPANS);

    runtimeContextFieldInjection =
        configProvider.getBoolean(
            RUNTIME_CONTEXT_FIELD_INJECTION, DEFAULT_RUNTIME_CONTEXT_FIELD_INJECTION);

    propagationStylesToExtract =
        getPropagationStyleSetSettingFromEnvironmentOrDefault(
            PROPAGATION_STYLE_EXTRACT, DEFAULT_PROPAGATION_STYLE_EXTRACT);
    propagationStylesToInject =
        getPropagationStyleSetSettingFromEnvironmentOrDefault(
            PROPAGATION_STYLE_INJECT, DEFAULT_PROPAGATION_STYLE_INJECT);

    boolean runtimeMetricsEnabled = configProvider.getBoolean(RUNTIME_METRICS_ENABLED, true);

    jmxFetchEnabled =
        runtimeMetricsEnabled
            && configProvider.getBoolean(JMX_FETCH_ENABLED, DEFAULT_JMX_FETCH_ENABLED);
    jmxFetchConfigDir = configProvider.getString(JMX_FETCH_CONFIG_DIR);
    jmxFetchConfigs = configProvider.getList(JMX_FETCH_CONFIG);
    jmxFetchMetricsConfigs = configProvider.getList(JMX_FETCH_METRICS_CONFIGS);
    jmxFetchCheckPeriod = configProvider.getInteger(JMX_FETCH_CHECK_PERIOD);
    jmxFetchRefreshBeansPeriod = configProvider.getInteger(JMX_FETCH_REFRESH_BEANS_PERIOD);
    jmxFetchStatsdHost = configProvider.getString(JMX_FETCH_STATSD_HOST);
    jmxFetchStatsdPort =
        configProvider.getInteger(JMX_FETCH_STATSD_PORT, DEFAULT_JMX_FETCH_STATSD_PORT);

    // Writer.Builder createMonitor will use the values of the JMX fetch & agent to fill-in defaults
    healthMetricsEnabled =
        runtimeMetricsEnabled
            && configProvider.getBoolean(HEALTH_METRICS_ENABLED, DEFAULT_HEALTH_METRICS_ENABLED);
    healthMetricsStatsdHost = configProvider.getString(HEALTH_METRICS_STATSD_HOST);
    healthMetricsStatsdPort = configProvider.getInteger(HEALTH_METRICS_STATSD_PORT);
    perfMetricsEnabled =
        runtimeMetricsEnabled
            && configProvider.getBoolean(PERF_METRICS_ENABLED, DEFAULT_PERF_METRICS_ENABLED);

    tracerMetricsEnabled = configProvider.getBoolean(TRACER_METRICS_ENABLED, false);
    tracerMetricsMaxAggregates = configProvider.getInteger(TRACER_METRICS_MAX_AGGREGATES, 1000);
    tracerMetricsMaxPending = configProvider.getInteger(TRACER_METRICS_MAX_PENDING, 2048);

    logsInjectionEnabled =
        configProvider.getBoolean(LOGS_INJECTION_ENABLED, DEFAULT_LOGS_INJECTION_ENABLED);
    logsMDCTagsInjectionEnabled = configProvider.getBoolean(LOGS_MDC_TAGS_INJECTION_ENABLED, false);
    reportHostName =
        configProvider.getBoolean(TRACE_REPORT_HOSTNAME, DEFAULT_TRACE_REPORT_HOSTNAME);

    traceAgentV05Enabled =
        configProvider.getBoolean(ENABLE_TRACE_AGENT_V05, DEFAULT_TRACE_AGENT_V05_ENABLED);

    traceAnnotations = configProvider.getString(TRACE_ANNOTATIONS, DEFAULT_TRACE_ANNOTATIONS);

    traceMethods = configProvider.getString(TRACE_METHODS, DEFAULT_TRACE_METHODS);

    traceExecutorsAll = configProvider.getBoolean(TRACE_EXECUTORS_ALL, DEFAULT_TRACE_EXECUTORS_ALL);

    traceExecutors = configProvider.getList(TRACE_EXECUTORS);

    traceAnalyticsEnabled =
        configProvider.getBoolean(TRACE_ANALYTICS_ENABLED, DEFAULT_TRACE_ANALYTICS_ENABLED);

    traceSamplingServiceRules = configProvider.getMergedMap(TRACE_SAMPLING_SERVICE_RULES);
    traceSamplingOperationRules = configProvider.getMergedMap(TRACE_SAMPLING_OPERATION_RULES);
    traceSampleRate = configProvider.getDouble(TRACE_SAMPLE_RATE);
    traceRateLimit = configProvider.getInteger(TRACE_RATE_LIMIT, DEFAULT_TRACE_RATE_LIMIT);

    profilingEnabled = configProvider.getBoolean(PROFILING_ENABLED, DEFAULT_PROFILING_ENABLED);
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

    kafkaClientPropagationEnabled =
        configProvider.getBoolean(
            KAFKA_CLIENT_PROPAGATION_ENABLED, DEFAULT_KAFKA_CLIENT_PROPAGATION_ENABLED);

    kafkaClientBase64DecodingEnabled =
        configProvider.getBoolean(KAFKA_CLIENT_BASE64_DECODING_ENABLED, false);

    hystrixTagsEnabled = configProvider.getBoolean(HYSTRIX_TAGS_ENABLED, false);

    servletPrincipalEnabled =
        configProvider.getBoolean(TraceInstrumentationConfig.SERVLET_PRINCIPAL_ENABLED, false);

    servletAsyncTimeoutError =
        configProvider.getBoolean(TraceInstrumentationConfig.SERVLET_ASYNC_TIMEOUT_ERROR, true);

    debugEnabled = isDebugMode();

    internalExitOnFailure =
        configProvider.getBoolean(GeneralConfig.INTERNAL_EXIT_ON_FAILURE, false);

    // Setting this last because we have a few places where this can come from
    apiKey = tmpApiKey;

    log.debug("New instance: {}", this);
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
    return new WellKnownTags(getHostName(), tags.get(ENV), serviceName, tags.get(VERSION));
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
      // when profilingUrl is set we use it regardless of apiKey
      return profilingUrl;
    } else if (apiKey != null) {
      // when profilingUrl is not set and apiKey is set we send directly to our intake
      return String.format(PROFILING_REMOTE_URL_TEMPLATE, site);
    } else {
      // when profilingUrl and apiKey are not set we send to the dd trace agent running locally
      return String.format(PROFILING_LOCAL_URL_TEMPLATE, agentHost, agentPort);
    }
  }

  public boolean isIntegrationEnabled(
      final SortedSet<String> integrationNames, final boolean defaultEnabled) {
    return isEnabled(integrationNames, "integration.", ".enabled", defaultEnabled);
  }

  public boolean isJmxFetchIntegrationEnabled(
      final SortedSet<String> integrationNames, final boolean defaultEnabled) {
    return isEnabled(integrationNames, "jmxfetch.", ".enabled", defaultEnabled);
  }

  public boolean isRuleEnabled(final String name) {
    return configProvider.getBoolean("trace." + name + ".enabled", true)
        && configProvider.getBoolean("trace." + name.toLowerCase() + ".enabled", true);
  }

  /**
   * @param integrationNames
   * @param defaultEnabled
   * @return
   * @deprecated This method should only be used internally. Use the instance getter instead {@link
   *     #isJmxFetchIntegrationEnabled(SortedSet, boolean)}.
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

  private static boolean isDebugMode() {
    final String tracerDebugLevelSysprop = "dd.trace.debug";
    final String tracerDebugLevelProp = System.getProperty(tracerDebugLevelSysprop);

    if (tracerDebugLevelProp != null) {
      return Boolean.parseBoolean(tracerDebugLevelProp);
    }

    final String tracerDebugLevelEnv =
        System.getenv(tracerDebugLevelSysprop.replace('.', '_').toUpperCase());

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
    // If default is enabled, we want to enable individually,
    // if default is disabled, we want to disable individually.
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

  /**
   * Converts the property name, e.g. 'service.name' into a public environment variable name, e.g.
   * `DD_SERVICE_NAME`.
   *
   * @param setting The setting name, e.g. `service.name`
   * @return The public facing environment variable name
   */
  @NonNull
  private static String propertyNameToEnvironmentVariableName(final String setting) {
    return ENV_REPLACEMENT
        .matcher(propertyNameToSystemPropertyName(setting).toUpperCase())
        .replaceAll("_");
  }

  private static final String PREFIX = "dd.";

  /**
   * Converts the property name, e.g. 'service.name' into a public system property name, e.g.
   * `dd.service.name`.
   *
   * @param setting The setting name, e.g. `service.name`
   * @return The public facing system property name
   */
  @NonNull
  private static String propertyNameToSystemPropertyName(final String setting) {
    return PREFIX + setting;
  }

  @NonNull
  private static Map<String, String> newHashMap(final int size) {
    return new HashMap<>(size + 1, 1f);
  }

  /**
   * @param map
   * @param propNames
   * @return new unmodifiable copy of {@param map} where properties are overwritten from environment
   */
  @NonNull
  private Map<String, String> getMapWithPropertiesDefinedByEnvironment(
      @NonNull final Map<String, String> map, @NonNull final String... propNames) {
    final Map<String, String> res = new HashMap<>(map);
    for (final String propName : propNames) {
      final String val = configProvider.getString(propName);
      if (val != null) {
        res.put(propName, val);
      }
    }
    return Collections.unmodifiableMap(res);
  }

  @NonNull
  private static Set<String> parseStringIntoSetOfNonEmptyStrings(final String str) {
    // Using LinkedHashSet to preserve original string order
    final Set<String> result = new LinkedHashSet<>();
    // Java returns single value when splitting an empty string. We do not need that value, so
    // we need to throw it out.
    for (final String value : str.split(SPLIT_BY_SPACE_OR_COMMA_REGEX)) {
      if (!value.isEmpty()) {
        result.add(value);
      }
    }
    return Collections.unmodifiableSet(result);
  }

  @NonNull
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

  private static String findConfigurationFile() {
    String configurationFilePath =
        System.getProperty(propertyNameToSystemPropertyName(CONFIGURATION_FILE));
    if (null == configurationFilePath) {
      configurationFilePath =
          System.getenv(propertyNameToEnvironmentVariableName(CONFIGURATION_FILE));
    }
    if (null != configurationFilePath) {
      configurationFilePath =
          configurationFilePath.replaceFirst("^~", System.getProperty("user.home"));
      final File configurationFile = new File(configurationFilePath);
      if (!configurationFile.exists()) {
        return configurationFilePath;
      }
    }
    return "no config file present";
  }

  /** Returns the detected hostname. First tries locally, then using DNS */
  private static String getHostName() {
    String possibleHostname;

    // Try environment variable.  This works in almost all environments
    if (System.getProperty("os.name").startsWith("Windows")) {
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

  // This has to be placed after all other static fields to give them a chance to initialize
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
}
