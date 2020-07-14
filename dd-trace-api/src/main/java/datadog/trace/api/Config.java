package datadog.trace.api;

import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_HOST;
import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_TIMEOUT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_UNIX_DOMAIN_SOCKET;
import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_WRITER_TYPE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_ANALYTICS_SAMPLE_RATE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE;
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
import static datadog.trace.api.ConfigDefaults.DEFAULT_METRICS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PARTIAL_FLUSH_MIN_SPANS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_PRIORITY_SAMPLING_ENABLED;
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
import static datadog.trace.api.ConfigDefaults.DEFAULT_SPLIT_BY_TAGS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_AGENT_PORT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_ANALYTICS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_ANNOTATIONS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_EXECUTORS;
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

import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.config.JmxFetchConfig;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.config.TraceInstrumentationConfig;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.env.CapturedEnvironment;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.InetAddress;
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
 * system property, but uppercased with '.' -> '_'.
 */
@Deprecated
@Slf4j
@ToString(includeFieldNames = true)
public class Config {
  private static final MethodHandles.Lookup PUBLIC_LOOKUP = MethodHandles.publicLookup();

  /** Config keys below */
  private static final String PREFIX = "dd.";

  public static final String CONFIGURATION_FILE = GeneralConfig.CONFIGURATION_FILE;
  public static final String API_KEY = GeneralConfig.API_KEY;
  public static final String API_KEY_FILE = GeneralConfig.API_KEY_FILE;
  public static final String SITE = GeneralConfig.SITE;
  public static final String SERVICE_NAME = GeneralConfig.SERVICE_NAME;
  public static final String TRACE_ENABLED = TraceInstrumentationConfig.TRACE_ENABLED;
  public static final String INTEGRATIONS_ENABLED = TraceInstrumentationConfig.INTEGRATIONS_ENABLED;
  public static final String WRITER_TYPE = TracerConfig.WRITER_TYPE;
  public static final String AGENT_HOST = TracerConfig.AGENT_HOST;
  public static final String TRACE_AGENT_PORT = TracerConfig.TRACE_AGENT_PORT;
  public static final String AGENT_PORT_LEGACY = TracerConfig.AGENT_PORT_LEGACY;
  public static final String AGENT_UNIX_DOMAIN_SOCKET = TracerConfig.AGENT_UNIX_DOMAIN_SOCKET;
  public static final String AGENT_TIMEOUT = TracerConfig.AGENT_TIMEOUT;
  public static final String PRIORITY_SAMPLING = TracerConfig.PRIORITY_SAMPLING;

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

  public static final String KAFKA_CLIENT_PROPAGATION_ENABLED =
      TraceInstrumentationConfig.KAFKA_CLIENT_PROPAGATION_ENABLED;

  public static final String KAFKA_CLIENT_BASE64_DECODING_ENABLED =
      TraceInstrumentationConfig.KAFKA_CLIENT_BASE64_DECODING_ENABLED;

  private static final String PROFILING_REMOTE_URL_TEMPLATE = "https://intake.profile.%s/v1/input";
  private static final String PROFILING_LOCAL_URL_TEMPLATE = "http://%s:%d/profiling/v1/input";

  private static final Pattern ENV_REPLACEMENT = Pattern.compile("[^a-zA-Z0-9_]");
  private static final String SPLIT_BY_SPACE_OR_COMMA_REGEX = "[,\\s]+";

  public enum PropagationStyle {
    DATADOG,
    B3,
    HAYSTACK
  }

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
  @Getter private final String agentHost;
  @Getter private final int agentPort;
  @Getter private final String agentUnixDomainSocket;
  @Getter private final int agentTimeout;
  @Getter private final boolean prioritySamplingEnabled;
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
  @Getter private final Integer scopeDepthLimit;
  @Getter private final boolean scopeStrictMode;
  @Getter private final Integer partialFlushMinSpans;
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

  @Getter private final boolean logsInjectionEnabled;
  @Getter private final boolean reportHostName;

  @Getter private final String traceAnnotations;

  @Getter private final String traceMethods;

  @Getter private final boolean traceExecutorsAll;
  @Getter private final List<String> traceExecutors;

  @Getter private final boolean traceAnalyticsEnabled;

  @Getter private final Map<String, String> traceSamplingServiceRules;
  @Getter private final Map<String, String> traceSamplingOperationRules;
  @Getter private final Double traceSampleRate;
  @Getter private final Double traceRateLimit;

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

  @Getter private final boolean kafkaClientPropagationEnabled;
  @Getter private final boolean kafkaClientBase64DecodingEnabled;

  // Values from an optionally provided properties file
  private static Properties propertiesFromConfigFile;

  // Values extracted from the environment. These properties are platform dependant.
  private static Properties propertiesFromCapturedEnv;

  // Read order: System Properties -> Env Variables, [-> properties file], [-> default value]
  // Visible for testing
  Config() {
    propertiesFromConfigFile = loadConfigurationFile();
    propertiesFromCapturedEnv = loadCapturedEnvironment();

    runtimeId = UUID.randomUUID().toString();

    // Note: We do not want APiKey to be loaded from property for security reasons
    // Note: we do not use defined default here
    // FIXME: We should use better authentication mechanism
    final String apiKeyFile = getSettingFromEnvironment(API_KEY_FILE, null);
    String tmpApiKey = System.getenv(propertyNameToEnvironmentVariableName(API_KEY));
    if (apiKeyFile != null) {
      try {
        tmpApiKey =
            new String(Files.readAllBytes(Paths.get(apiKeyFile)), StandardCharsets.UTF_8).trim();
      } catch (final IOException e) {
        log.error("Cannot read API key from file {}, skipping", apiKeyFile, e);
      }
    }
    site = getSettingFromEnvironment(SITE, DEFAULT_SITE);
    serviceName =
        getSettingFromEnvironment(
            SERVICE, getSettingFromEnvironment(SERVICE_NAME, DEFAULT_SERVICE_NAME));

    traceEnabled = getBooleanSettingFromEnvironment(TRACE_ENABLED, DEFAULT_TRACE_ENABLED);
    integrationsEnabled =
        getBooleanSettingFromEnvironment(INTEGRATIONS_ENABLED, DEFAULT_INTEGRATIONS_ENABLED);
    writerType = getSettingFromEnvironment(WRITER_TYPE, DEFAULT_AGENT_WRITER_TYPE);
    agentHost = getSettingFromEnvironment(AGENT_HOST, DEFAULT_AGENT_HOST);
    agentPort =
        getIntegerSettingFromEnvironment(
            TRACE_AGENT_PORT,
            getIntegerSettingFromEnvironment(AGENT_PORT_LEGACY, DEFAULT_TRACE_AGENT_PORT));
    agentUnixDomainSocket =
        getSettingFromEnvironment(AGENT_UNIX_DOMAIN_SOCKET, DEFAULT_AGENT_UNIX_DOMAIN_SOCKET);
    agentTimeout = getIntegerSettingFromEnvironment(AGENT_TIMEOUT, DEFAULT_AGENT_TIMEOUT);
    prioritySamplingEnabled =
        getBooleanSettingFromEnvironment(PRIORITY_SAMPLING, DEFAULT_PRIORITY_SAMPLING_ENABLED);
    traceResolverEnabled =
        getBooleanSettingFromEnvironment(TRACE_RESOLVER_ENABLED, DEFAULT_TRACE_RESOLVER_ENABLED);
    serviceMapping = getMapSettingFromEnvironment(SERVICE_MAPPING, null);

    {
      final Map<String, String> tags =
          new HashMap<>(getMapSettingFromEnvironment(GLOBAL_TAGS, null));
      tags.putAll(getMapSettingFromEnvironment(TAGS, null));
      this.tags = getMapWithPropertiesDefinedByEnvironment(tags, ENV, VERSION);
    }

    spanTags = getMapSettingFromEnvironment(SPAN_TAGS, null);
    jmxTags = getMapSettingFromEnvironment(JMX_TAGS, null);

    excludedClasses = getListSettingFromEnvironment(TRACE_CLASSES_EXCLUDE, null);
    headerTags = getMapSettingFromEnvironment(HEADER_TAGS, null);

    httpServerErrorStatuses =
        getIntegerRangeSettingFromEnvironment(
            HTTP_SERVER_ERROR_STATUSES, DEFAULT_HTTP_SERVER_ERROR_STATUSES);

    httpClientErrorStatuses =
        getIntegerRangeSettingFromEnvironment(
            HTTP_CLIENT_ERROR_STATUSES, DEFAULT_HTTP_CLIENT_ERROR_STATUSES);

    httpServerTagQueryString =
        getBooleanSettingFromEnvironment(
            HTTP_SERVER_TAG_QUERY_STRING, DEFAULT_HTTP_SERVER_TAG_QUERY_STRING);

    httpClientTagQueryString =
        getBooleanSettingFromEnvironment(
            HTTP_CLIENT_TAG_QUERY_STRING, DEFAULT_HTTP_CLIENT_TAG_QUERY_STRING);

    httpClientSplitByDomain =
        getBooleanSettingFromEnvironment(
            HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, DEFAULT_HTTP_CLIENT_SPLIT_BY_DOMAIN);

    dbClientSplitByInstance =
        getBooleanSettingFromEnvironment(
            DB_CLIENT_HOST_SPLIT_BY_INSTANCE, DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE);

    splitByTags =
        Collections.unmodifiableSet(
            new LinkedHashSet<>(
                getListSettingFromEnvironment(SPLIT_BY_TAGS, DEFAULT_SPLIT_BY_TAGS)));

    scopeDepthLimit =
        getIntegerSettingFromEnvironment(SCOPE_DEPTH_LIMIT, DEFAULT_SCOPE_DEPTH_LIMIT);

    scopeStrictMode = getBooleanSettingFromEnvironment(SCOPE_STRICT_MODE, false);

    partialFlushMinSpans =
        getIntegerSettingFromEnvironment(PARTIAL_FLUSH_MIN_SPANS, DEFAULT_PARTIAL_FLUSH_MIN_SPANS);

    runtimeContextFieldInjection =
        getBooleanSettingFromEnvironment(
            RUNTIME_CONTEXT_FIELD_INJECTION, DEFAULT_RUNTIME_CONTEXT_FIELD_INJECTION);

    propagationStylesToExtract =
        getPropagationStyleSetSettingFromEnvironmentOrDefault(
            PROPAGATION_STYLE_EXTRACT, DEFAULT_PROPAGATION_STYLE_EXTRACT);
    propagationStylesToInject =
        getPropagationStyleSetSettingFromEnvironmentOrDefault(
            PROPAGATION_STYLE_INJECT, DEFAULT_PROPAGATION_STYLE_INJECT);

    jmxFetchEnabled =
        getBooleanSettingFromEnvironment(JMX_FETCH_ENABLED, DEFAULT_JMX_FETCH_ENABLED);
    jmxFetchConfigDir = getSettingFromEnvironment(JMX_FETCH_CONFIG_DIR, null);
    jmxFetchConfigs = getListSettingFromEnvironment(JMX_FETCH_CONFIG, null);
    jmxFetchMetricsConfigs = getListSettingFromEnvironment(JMX_FETCH_METRICS_CONFIGS, null);
    jmxFetchCheckPeriod = getIntegerSettingFromEnvironment(JMX_FETCH_CHECK_PERIOD, null);
    jmxFetchRefreshBeansPeriod =
        getIntegerSettingFromEnvironment(JMX_FETCH_REFRESH_BEANS_PERIOD, null);
    jmxFetchStatsdHost = getSettingFromEnvironment(JMX_FETCH_STATSD_HOST, null);
    jmxFetchStatsdPort =
        getIntegerSettingFromEnvironment(JMX_FETCH_STATSD_PORT, DEFAULT_JMX_FETCH_STATSD_PORT);

    // Writer.Builder createMonitor will use the values of the JMX fetch & agent to fill-in defaults
    healthMetricsEnabled =
        getBooleanSettingFromEnvironment(HEALTH_METRICS_ENABLED, DEFAULT_METRICS_ENABLED);
    healthMetricsStatsdHost = getSettingFromEnvironment(HEALTH_METRICS_STATSD_HOST, null);
    healthMetricsStatsdPort = getIntegerSettingFromEnvironment(HEALTH_METRICS_STATSD_PORT, null);

    logsInjectionEnabled =
        getBooleanSettingFromEnvironment(LOGS_INJECTION_ENABLED, DEFAULT_LOGS_INJECTION_ENABLED);
    reportHostName =
        getBooleanSettingFromEnvironment(TRACE_REPORT_HOSTNAME, DEFAULT_TRACE_REPORT_HOSTNAME);

    traceAnnotations = getSettingFromEnvironment(TRACE_ANNOTATIONS, DEFAULT_TRACE_ANNOTATIONS);

    traceMethods = getSettingFromEnvironment(TRACE_METHODS, DEFAULT_TRACE_METHODS);

    traceExecutorsAll =
        getBooleanSettingFromEnvironment(TRACE_EXECUTORS_ALL, DEFAULT_TRACE_EXECUTORS_ALL);

    traceExecutors = getListSettingFromEnvironment(TRACE_EXECUTORS, DEFAULT_TRACE_EXECUTORS);

    traceAnalyticsEnabled =
        getBooleanSettingFromEnvironment(TRACE_ANALYTICS_ENABLED, DEFAULT_TRACE_ANALYTICS_ENABLED);

    traceSamplingServiceRules = getMapSettingFromEnvironment(TRACE_SAMPLING_SERVICE_RULES, null);
    traceSamplingOperationRules =
        getMapSettingFromEnvironment(TRACE_SAMPLING_OPERATION_RULES, null);
    traceSampleRate = getDoubleSettingFromEnvironment(TRACE_SAMPLE_RATE, null);
    traceRateLimit = getDoubleSettingFromEnvironment(TRACE_RATE_LIMIT, DEFAULT_TRACE_RATE_LIMIT);

    profilingEnabled =
        getBooleanSettingFromEnvironment(PROFILING_ENABLED, DEFAULT_PROFILING_ENABLED);
    profilingUrl = getSettingFromEnvironment(PROFILING_URL, null);

    if (tmpApiKey == null) {
      final String oldProfilingApiKeyFile =
          getSettingFromEnvironment(PROFILING_API_KEY_FILE_OLD, null);
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
          getSettingFromEnvironment(PROFILING_API_KEY_FILE_VERY_OLD, null);
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

    profilingTags = getMapSettingFromEnvironment(PROFILING_TAGS, null);
    profilingStartDelay =
        getIntegerSettingFromEnvironment(PROFILING_START_DELAY, DEFAULT_PROFILING_START_DELAY);
    profilingStartForceFirst =
        getBooleanSettingFromEnvironment(
            PROFILING_START_FORCE_FIRST, DEFAULT_PROFILING_START_FORCE_FIRST);
    profilingUploadPeriod =
        getIntegerSettingFromEnvironment(PROFILING_UPLOAD_PERIOD, DEFAULT_PROFILING_UPLOAD_PERIOD);
    profilingTemplateOverrideFile =
        getSettingFromEnvironment(PROFILING_TEMPLATE_OVERRIDE_FILE, null);
    profilingUploadTimeout =
        getIntegerSettingFromEnvironment(
            PROFILING_UPLOAD_TIMEOUT, DEFAULT_PROFILING_UPLOAD_TIMEOUT);
    profilingUploadCompression =
        getSettingFromEnvironment(
            PROFILING_UPLOAD_COMPRESSION, DEFAULT_PROFILING_UPLOAD_COMPRESSION);
    profilingProxyHost = getSettingFromEnvironment(PROFILING_PROXY_HOST, null);
    profilingProxyPort =
        getIntegerSettingFromEnvironment(PROFILING_PROXY_PORT, DEFAULT_PROFILING_PROXY_PORT);
    profilingProxyUsername = getSettingFromEnvironment(PROFILING_PROXY_USERNAME, null);
    profilingProxyPassword = getSettingFromEnvironment(PROFILING_PROXY_PASSWORD, null);

    profilingExceptionSampleLimit =
        getIntegerSettingFromEnvironment(
            PROFILING_EXCEPTION_SAMPLE_LIMIT, DEFAULT_PROFILING_EXCEPTION_SAMPLE_LIMIT);
    profilingExceptionHistogramTopItems =
        getIntegerSettingFromEnvironment(
            PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS,
            DEFAULT_PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS);
    profilingExceptionHistogramMaxCollectionSize =
        getIntegerSettingFromEnvironment(
            PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE,
            DEFAULT_PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE);

    kafkaClientPropagationEnabled =
        getBooleanSettingFromEnvironment(
            KAFKA_CLIENT_PROPAGATION_ENABLED, DEFAULT_KAFKA_CLIENT_PROPAGATION_ENABLED);

    kafkaClientBase64DecodingEnabled =
        getBooleanSettingFromEnvironment(KAFKA_CLIENT_BASE64_DECODING_ENABLED, false);

    // Setting this last because we have a few places where this can come from
    apiKey = tmpApiKey;

    log.debug("New instance: {}", this);
  }

  // Read order: Properties -> Parent
  private Config(final Properties properties, final Config parent) {
    runtimeId = parent.runtimeId;

    apiKey = properties.getProperty(API_KEY, parent.apiKey);
    site = properties.getProperty(SITE, parent.site);
    serviceName =
        properties.getProperty(SERVICE, properties.getProperty(SERVICE_NAME, parent.serviceName));

    traceEnabled = getPropertyBooleanValue(properties, TRACE_ENABLED, parent.traceEnabled);
    integrationsEnabled =
        getPropertyBooleanValue(properties, INTEGRATIONS_ENABLED, parent.integrationsEnabled);
    writerType = properties.getProperty(WRITER_TYPE, parent.writerType);
    agentHost = properties.getProperty(AGENT_HOST, parent.agentHost);
    agentPort =
        getPropertyIntegerValue(
            properties,
            TRACE_AGENT_PORT,
            getPropertyIntegerValue(properties, AGENT_PORT_LEGACY, parent.agentPort));
    agentUnixDomainSocket =
        properties.getProperty(AGENT_UNIX_DOMAIN_SOCKET, parent.agentUnixDomainSocket);
    agentTimeout = getPropertyIntegerValue(properties, AGENT_TIMEOUT, parent.agentTimeout);
    prioritySamplingEnabled =
        getPropertyBooleanValue(properties, PRIORITY_SAMPLING, parent.prioritySamplingEnabled);
    traceResolverEnabled =
        getPropertyBooleanValue(properties, TRACE_RESOLVER_ENABLED, parent.traceResolverEnabled);
    serviceMapping = getPropertyMapValue(properties, SERVICE_MAPPING, parent.serviceMapping);

    {
      final Map<String, String> preTags =
          new HashMap<>(
              getPropertyMapValue(properties, GLOBAL_TAGS, Collections.<String, String>emptyMap()));
      preTags.putAll(getPropertyMapValue(properties, TAGS, parent.tags));
      tags = overwriteKeysFromProperties(preTags, properties, ENV, VERSION);
    }
    spanTags = getPropertyMapValue(properties, SPAN_TAGS, parent.spanTags);
    jmxTags = getPropertyMapValue(properties, JMX_TAGS, parent.jmxTags);
    excludedClasses =
        getPropertyListValue(properties, TRACE_CLASSES_EXCLUDE, parent.excludedClasses);
    headerTags = getPropertyMapValue(properties, HEADER_TAGS, parent.headerTags);

    httpServerErrorStatuses =
        getPropertyIntegerRangeValue(
            properties, HTTP_SERVER_ERROR_STATUSES, parent.httpServerErrorStatuses);

    httpClientErrorStatuses =
        getPropertyIntegerRangeValue(
            properties, HTTP_CLIENT_ERROR_STATUSES, parent.httpClientErrorStatuses);

    httpServerTagQueryString =
        getPropertyBooleanValue(
            properties, HTTP_SERVER_TAG_QUERY_STRING, parent.httpServerTagQueryString);

    httpClientTagQueryString =
        getPropertyBooleanValue(
            properties, HTTP_CLIENT_TAG_QUERY_STRING, parent.httpClientTagQueryString);

    httpClientSplitByDomain =
        getPropertyBooleanValue(
            properties, HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, parent.httpClientSplitByDomain);

    dbClientSplitByInstance =
        getPropertyBooleanValue(
            properties, DB_CLIENT_HOST_SPLIT_BY_INSTANCE, parent.dbClientSplitByInstance);

    splitByTags =
        Collections.unmodifiableSet(
            new LinkedHashSet<>(
                getPropertyListValue(
                    properties, SPLIT_BY_TAGS, new ArrayList<>(parent.splitByTags))));

    scopeDepthLimit =
        getPropertyIntegerValue(properties, SCOPE_DEPTH_LIMIT, parent.scopeDepthLimit);

    scopeStrictMode =
        getPropertyBooleanValue(properties, SCOPE_STRICT_MODE, parent.scopeStrictMode);

    partialFlushMinSpans =
        getPropertyIntegerValue(properties, PARTIAL_FLUSH_MIN_SPANS, parent.partialFlushMinSpans);

    runtimeContextFieldInjection =
        getPropertyBooleanValue(
            properties, RUNTIME_CONTEXT_FIELD_INJECTION, parent.runtimeContextFieldInjection);

    final Set<PropagationStyle> parsedPropagationStylesToExtract =
        getPropagationStyleSetFromPropertyValue(properties, PROPAGATION_STYLE_EXTRACT);
    propagationStylesToExtract =
        parsedPropagationStylesToExtract == null
            ? parent.propagationStylesToExtract
            : parsedPropagationStylesToExtract;
    final Set<PropagationStyle> parsedPropagationStylesToInject =
        getPropagationStyleSetFromPropertyValue(properties, PROPAGATION_STYLE_INJECT);
    propagationStylesToInject =
        parsedPropagationStylesToInject == null
            ? parent.propagationStylesToInject
            : parsedPropagationStylesToInject;

    jmxFetchEnabled =
        getPropertyBooleanValue(properties, JMX_FETCH_ENABLED, parent.jmxFetchEnabled);
    jmxFetchConfigDir = properties.getProperty(JMX_FETCH_CONFIG_DIR, parent.jmxFetchConfigDir);
    jmxFetchConfigs = getPropertyListValue(properties, JMX_FETCH_CONFIG, parent.jmxFetchConfigs);
    jmxFetchMetricsConfigs =
        getPropertyListValue(properties, JMX_FETCH_METRICS_CONFIGS, parent.jmxFetchMetricsConfigs);
    jmxFetchCheckPeriod =
        getPropertyIntegerValue(properties, JMX_FETCH_CHECK_PERIOD, parent.jmxFetchCheckPeriod);
    jmxFetchRefreshBeansPeriod =
        getPropertyIntegerValue(
            properties, JMX_FETCH_REFRESH_BEANS_PERIOD, parent.jmxFetchRefreshBeansPeriod);
    jmxFetchStatsdHost = properties.getProperty(JMX_FETCH_STATSD_HOST, parent.jmxFetchStatsdHost);
    jmxFetchStatsdPort =
        getPropertyIntegerValue(properties, JMX_FETCH_STATSD_PORT, parent.jmxFetchStatsdPort);

    healthMetricsEnabled =
        getPropertyBooleanValue(properties, HEALTH_METRICS_ENABLED, DEFAULT_METRICS_ENABLED);
    healthMetricsStatsdHost =
        properties.getProperty(HEALTH_METRICS_STATSD_HOST, parent.healthMetricsStatsdHost);
    healthMetricsStatsdPort =
        getPropertyIntegerValue(
            properties, HEALTH_METRICS_STATSD_PORT, parent.healthMetricsStatsdPort);

    logsInjectionEnabled =
        getBooleanSettingFromEnvironment(LOGS_INJECTION_ENABLED, DEFAULT_LOGS_INJECTION_ENABLED);
    reportHostName =
        getPropertyBooleanValue(properties, TRACE_REPORT_HOSTNAME, parent.reportHostName);

    traceAnnotations = properties.getProperty(TRACE_ANNOTATIONS, parent.traceAnnotations);

    traceMethods = properties.getProperty(TRACE_METHODS, parent.traceMethods);

    traceExecutorsAll =
        getPropertyBooleanValue(properties, TRACE_EXECUTORS_ALL, parent.traceExecutorsAll);
    traceExecutors = getPropertyListValue(properties, TRACE_EXECUTORS, parent.traceExecutors);

    traceAnalyticsEnabled =
        getPropertyBooleanValue(properties, TRACE_ANALYTICS_ENABLED, parent.traceAnalyticsEnabled);

    traceSamplingServiceRules =
        getPropertyMapValue(
            properties, TRACE_SAMPLING_SERVICE_RULES, parent.traceSamplingServiceRules);
    traceSamplingOperationRules =
        getPropertyMapValue(
            properties, TRACE_SAMPLING_OPERATION_RULES, parent.traceSamplingOperationRules);
    traceSampleRate = getPropertyDoubleValue(properties, TRACE_SAMPLE_RATE, parent.traceSampleRate);
    traceRateLimit = getPropertyDoubleValue(properties, TRACE_RATE_LIMIT, parent.traceRateLimit);

    profilingEnabled =
        getPropertyBooleanValue(properties, PROFILING_ENABLED, parent.profilingEnabled);
    profilingUrl = properties.getProperty(PROFILING_URL, parent.profilingUrl);
    profilingTags = getPropertyMapValue(properties, PROFILING_TAGS, parent.profilingTags);
    profilingStartDelay =
        getPropertyIntegerValue(properties, PROFILING_START_DELAY, parent.profilingStartDelay);
    profilingStartForceFirst =
        getPropertyBooleanValue(
            properties, PROFILING_START_FORCE_FIRST, parent.profilingStartForceFirst);
    profilingUploadPeriod =
        getPropertyIntegerValue(properties, PROFILING_UPLOAD_PERIOD, parent.profilingUploadPeriod);
    profilingTemplateOverrideFile =
        properties.getProperty(
            PROFILING_TEMPLATE_OVERRIDE_FILE, parent.profilingTemplateOverrideFile);
    profilingUploadTimeout =
        getPropertyIntegerValue(
            properties, PROFILING_UPLOAD_TIMEOUT, parent.profilingUploadTimeout);
    profilingUploadCompression =
        properties.getProperty(PROFILING_UPLOAD_COMPRESSION, parent.profilingUploadCompression);
    profilingProxyHost = properties.getProperty(PROFILING_PROXY_HOST, parent.profilingProxyHost);
    profilingProxyPort =
        getPropertyIntegerValue(properties, PROFILING_PROXY_PORT, parent.profilingProxyPort);
    profilingProxyUsername =
        properties.getProperty(PROFILING_PROXY_USERNAME, parent.profilingProxyUsername);
    profilingProxyPassword =
        properties.getProperty(PROFILING_PROXY_PASSWORD, parent.profilingProxyPassword);

    profilingExceptionSampleLimit =
        getPropertyIntegerValue(
            properties, PROFILING_EXCEPTION_SAMPLE_LIMIT, parent.profilingExceptionSampleLimit);

    profilingExceptionHistogramTopItems =
        getPropertyIntegerValue(
            properties,
            PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS,
            parent.profilingExceptionHistogramTopItems);
    profilingExceptionHistogramMaxCollectionSize =
        getPropertyIntegerValue(
            properties,
            PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE,
            parent.profilingExceptionHistogramMaxCollectionSize);

    kafkaClientPropagationEnabled =
        getPropertyBooleanValue(
            properties, KAFKA_CLIENT_PROPAGATION_ENABLED, parent.kafkaClientPropagationEnabled);

    kafkaClientBase64DecodingEnabled =
        getPropertyBooleanValue(
            properties,
            KAFKA_CLIENT_BASE64_DECODING_ENABLED,
            parent.kafkaClientBase64DecodingEnabled);

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
      final Float rate = getFloatSettingFromEnvironment(alias + ".analytics.sample-rate", null);
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
    return integrationEnabled(integrationNames, defaultEnabled);
  }

  /**
   * @param integrationNames
   * @param defaultEnabled
   * @return
   * @deprecated This method should only be used internally. Use the instance getter instead {@link
   *     #isIntegrationEnabled(SortedSet, boolean)}.
   */
  @Deprecated
  private static boolean integrationEnabled(
      final SortedSet<String> integrationNames, final boolean defaultEnabled) {
    // If default is enabled, we want to enable individually,
    // if default is disabled, we want to disable individually.
    boolean anyEnabled = defaultEnabled;
    for (final String name : integrationNames) {
      final boolean configEnabled =
          getBooleanSettingFromEnvironment("integration." + name + ".enabled", defaultEnabled);
      if (defaultEnabled) {
        anyEnabled &= configEnabled;
      } else {
        anyEnabled |= configEnabled;
      }
    }
    return anyEnabled;
  }

  public boolean isJmxFetchIntegrationEnabled(
      final SortedSet<String> integrationNames, final boolean defaultEnabled) {
    return jmxFetchIntegrationEnabled(integrationNames, defaultEnabled);
  }

  public boolean isRuleEnabled(final String name) {
    return getBooleanSettingFromEnvironment("trace." + name + ".enabled", true)
        && getBooleanSettingFromEnvironment("trace." + name.toLowerCase() + ".enabled", true);
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
    // If default is enabled, we want to enable individually,
    // if default is disabled, we want to disable individually.
    boolean anyEnabled = defaultEnabled;
    for (final String name : integrationNames) {
      final boolean configEnabled =
          getBooleanSettingFromEnvironment("jmxfetch." + name + ".enabled", defaultEnabled);
      if (defaultEnabled) {
        anyEnabled &= configEnabled;
      } else {
        anyEnabled |= configEnabled;
      }
    }
    return anyEnabled;
  }

  public boolean isEndToEndDurationEnabled(
      final boolean defaultEnabled, final String... integrationNames) {
    return isEnabled(integrationNames, ".e2e.duration.enabled", defaultEnabled);
  }

  public boolean isTraceAnalyticsIntegrationEnabled(
      final SortedSet<String> integrationNames, final boolean defaultEnabled) {
    return traceAnalyticsIntegrationEnabled(integrationNames, defaultEnabled);
  }

  public boolean isTraceAnalyticsIntegrationEnabled(
      final boolean defaultEnabled, final String... integrationNames) {
    return isEnabled(integrationNames, ".analytics.enabled", defaultEnabled);
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
    // If default is enabled, we want to enable individually,
    // if default is disabled, we want to disable individually.
    boolean anyEnabled = defaultEnabled;
    for (final String name : integrationNames) {
      final boolean configEnabled =
          getBooleanSettingFromEnvironment(name + ".analytics.enabled", defaultEnabled);
      if (defaultEnabled) {
        anyEnabled &= configEnabled;
      } else {
        anyEnabled |= configEnabled;
      }
    }
    return anyEnabled;
  }

  private static boolean isEnabled(
      final String[] integrationNames, final String settingSuffix, final boolean defaultEnabled) {
    // If default is enabled, we want to enable individually,
    // if default is disabled, we want to disable individually.
    boolean anyEnabled = defaultEnabled;
    for (final String name : integrationNames) {
      final boolean configEnabled =
          getBooleanSettingFromEnvironment(name + settingSuffix, defaultEnabled);
      if (defaultEnabled) {
        anyEnabled &= configEnabled;
      } else {
        anyEnabled |= configEnabled;
      }
    }
    return anyEnabled;
  }

  /**
   * Helper method that takes the name, adds a "dd." prefix then checks for System Properties of
   * that name. If none found, the name is converted to an Environment Variable and used to check
   * the env. If none of the above returns a value, then an optional properties file if checked. If
   * none found, then platform dependant properties are checked. If setting is not configured in
   * either location, <code>defaultValue</code> is returned.
   *
   * @param name
   * @param defaultValue
   * @return
   * @deprecated This method should only be used internally. Use the explicit getter instead.
   */
  private static String getSettingFromEnvironment(final String name, final String defaultValue) {
    String value;
    final String systemPropertyName = propertyNameToSystemPropertyName(name);

    // System properties and properties provided from command line have the highest precedence
    value = System.getProperties().getProperty(systemPropertyName);
    if (null != value) {
      return value;
    }

    // If value not provided from system properties, looking at env variables
    value = System.getenv(propertyNameToEnvironmentVariableName(name));
    if (null != value) {
      return value;
    }

    // If value is not defined yet, we look at properties optionally defined in a properties file
    value = propertiesFromConfigFile.getProperty(systemPropertyName);
    if (null != value) {
      return value;
    }

    // If value is not defined yet, we look at properties dependant of the platform.
    value = propertiesFromCapturedEnv.getProperty(name);
    if (null != value) {
      return value;
    }

    return defaultValue;
  }

  /** @deprecated This method should only be used internally. Use the explicit getter instead. */
  @NonNull
  private static Map<String, String> getMapSettingFromEnvironment(
      final String name, final String defaultValue) {
    return parseMap(
        getSettingFromEnvironment(name, defaultValue), propertyNameToSystemPropertyName(name));
  }

  /**
   * Calls {@link #getSettingFromEnvironment(String, String)} and converts the result to a list by
   * splitting on `,`.
   *
   * @deprecated This method should only be used internally. Use the explicit getter instead.
   */
  @NonNull
  private static List<String> getListSettingFromEnvironment(
      final String name, final String defaultValue) {
    return parseList(getSettingFromEnvironment(name, defaultValue));
  }

  /**
   * Calls {@link #getSettingFromEnvironment(String, String)} and converts the result to a Boolean.
   *
   * @deprecated This method should only be used internally. Use the explicit getter instead.
   */
  public static Boolean getBooleanSettingFromEnvironment(
      final String name, final Boolean defaultValue) {
    return getSettingFromEnvironmentWithLog(name, Boolean.class, defaultValue);
  }

  /**
   * Calls {@link #getSettingFromEnvironment(String, String)} and converts the result to a Float.
   *
   * @deprecated This method should only be used internally. Use the explicit getter instead.
   */
  public static Float getFloatSettingFromEnvironment(final String name, final Float defaultValue) {
    return getSettingFromEnvironmentWithLog(name, Float.class, defaultValue);
  }

  /**
   * Calls {@link #getSettingFromEnvironment(String, String)} and converts the result to a Double.
   *
   * @deprecated This method should only be used internally. Use the explicit getter instead.
   */
  @Deprecated
  private static Double getDoubleSettingFromEnvironment(
      final String name, final Double defaultValue) {
    return getSettingFromEnvironmentWithLog(name, Double.class, defaultValue);
  }

  /**
   * Calls {@link #getSettingFromEnvironment(String, String)} and converts the result to a Integer.
   */
  private static Integer getIntegerSettingFromEnvironment(
      final String name, final Integer defaultValue) {
    return getSettingFromEnvironmentWithLog(name, Integer.class, defaultValue);
  }

  private static <T> T getSettingFromEnvironmentWithLog(
      final String name, final Class<T> tClass, final T defaultValue) {
    try {
      return valueOf(getSettingFromEnvironment(name, null), tClass, defaultValue);
    } catch (final NumberFormatException e) {
      log.warn("Invalid configuration for " + name, e);
      return defaultValue;
    }
  }

  /**
   * Calls {@link #getSettingFromEnvironment(String, String)} and converts the result to a set of
   * strings splitting by space or comma.
   */
  private static Set<PropagationStyle> getPropagationStyleSetSettingFromEnvironmentOrDefault(
      final String name, final String defaultValue) {
    final String value = getSettingFromEnvironment(name, defaultValue);
    Set<PropagationStyle> result =
        convertStringSetToPropagationStyleSet(parseStringIntoSetOfNonEmptyStrings(value));

    if (result.isEmpty()) {
      // Treat empty parsing result as no value and use default instead
      result =
          convertStringSetToPropagationStyleSet(parseStringIntoSetOfNonEmptyStrings(defaultValue));
    }

    return result;
  }

  private static BitSet getIntegerRangeSettingFromEnvironment(
      final String name, final BitSet defaultValue) {
    final String value = getSettingFromEnvironment(name, null);
    try {
      return value == null ? defaultValue : parseIntegerRangeSet(value, name);
    } catch (final NumberFormatException e) {
      log.warn("Invalid configuration for " + name, e);
      return defaultValue;
    }
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

  /**
   * @param value to parse by tClass::valueOf
   * @param tClass should contain static parsing method "T valueOf(String)"
   * @param defaultValue
   * @param <T>
   * @return value == null || value.trim().isEmpty() ? defaultValue : tClass.valueOf(value)
   * @throws NumberFormatException
   */
  private static <T> T valueOf(
      final String value, @NonNull final Class<T> tClass, final T defaultValue) {
    if (value == null || value.trim().isEmpty()) {
      return defaultValue;
    }
    try {
      return (T)
          PUBLIC_LOOKUP
              .findStatic(tClass, "valueOf", MethodType.methodType(tClass, String.class))
              .invoke(value);
    } catch (final NumberFormatException e) {
      throw e;
    } catch (final NoSuchMethodException | IllegalAccessException e) {
      log.debug("Can't invoke or access 'valueOf': ", e);
      throw new NumberFormatException(e.toString());
    } catch (final Throwable e) {
      log.debug("Can't parse: ", e);
      throw new NumberFormatException(e.toString());
    }
  }

  private static Map<String, String> getPropertyMapValue(
      final Properties properties, final String name, final Map<String, String> defaultValue) {
    final String value = properties.getProperty(name);
    return value == null || value.trim().isEmpty() ? defaultValue : parseMap(value, name);
  }

  private static List<String> getPropertyListValue(
      final Properties properties, final String name, final List<String> defaultValue) {
    final String value = properties.getProperty(name);
    return value == null || value.trim().isEmpty() ? defaultValue : parseList(value);
  }

  private static Boolean getPropertyBooleanValue(
      final Properties properties, final String name, final Boolean defaultValue) {
    return valueOf(properties.getProperty(name), Boolean.class, defaultValue);
  }

  private static Integer getPropertyIntegerValue(
      final Properties properties, final String name, final Integer defaultValue) {
    return valueOf(properties.getProperty(name), Integer.class, defaultValue);
  }

  private static Double getPropertyDoubleValue(
      final Properties properties, final String name, final Double defaultValue) {
    return valueOf(properties.getProperty(name), Double.class, defaultValue);
  }

  private static Set<PropagationStyle> getPropagationStyleSetFromPropertyValue(
      final Properties properties, final String name) {
    final String value = properties.getProperty(name);
    if (value != null) {
      final Set<PropagationStyle> result =
          convertStringSetToPropagationStyleSet(parseStringIntoSetOfNonEmptyStrings(value));
      if (!result.isEmpty()) {
        return result;
      }
    }
    // null means parent value should be used
    return null;
  }

  private static BitSet getPropertyIntegerRangeValue(
      final Properties properties, final String name, final BitSet defaultValue) {
    final String value = properties.getProperty(name);
    try {
      return value == null ? defaultValue : parseIntegerRangeSet(value, name);
    } catch (final NumberFormatException e) {
      log.warn("Invalid configuration for " + name, e);
      return defaultValue;
    }
  }

  @NonNull
  private static Map<String, String> parseMap(final String str, final String settingName) {
    // If we ever want to have default values besides an empty map, this will need to change.
    if (str == null || str.trim().isEmpty()) {
      return Collections.emptyMap();
    }
    if (!str.matches("(([^,:]+:[^,:]*,)*([^,:]+:[^,:]*),?)?")) {
      log.warn(
          "Invalid config for {}: '{}'. Must match 'key1:value1,key2:value2'.", settingName, str);
      return Collections.emptyMap();
    }

    final String[] tokens = str.split(",", -1);
    final Map<String, String> map = newHashMap(tokens.length);

    for (final String token : tokens) {
      final String[] keyValue = token.split(":", -1);
      if (keyValue.length == 2) {
        final String key = keyValue[0].trim();
        final String value = keyValue[1].trim();
        if (value.length() <= 0) {
          log.warn("Ignoring empty value for key '{}' in config for {}", key, settingName);
          continue;
        }
        map.put(key, value);
      }
    }
    return Collections.unmodifiableMap(map);
  }

  @NonNull
  static BitSet parseIntegerRangeSet(@NonNull String str, final String settingName)
      throws NumberFormatException {
    str = str.replaceAll("\\s", "");
    if (!str.matches("\\d{3}(?:-\\d{3})?(?:,\\d{3}(?:-\\d{3})?)*")) {
      log.warn(
          "Invalid config for {}: '{}'. Must be formatted like '400-403,405,410-499'.",
          settingName,
          str);
      throw new NumberFormatException();
    }

    final int lastSeparator = Math.max(str.lastIndexOf(','), str.lastIndexOf('-'));
    final int maxValue = Integer.parseInt(str.substring(lastSeparator + 1));
    final BitSet set = new BitSet(maxValue);
    final String[] tokens = str.split(",", -1);
    for (final String token : tokens) {
      final int separator = token.indexOf('-');
      if (separator == -1) {
        set.set(Integer.parseInt(token));
      } else if (separator > 0) {
        final int left = Integer.parseInt(token.substring(0, separator));
        final int right = Integer.parseInt(token.substring(separator + 1));
        final int min = Math.min(left, right);
        final int max = Math.max(left, right);
        set.set(min, max + 1);
      }
    }
    return set;
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
  private static Map<String, String> getMapWithPropertiesDefinedByEnvironment(
      @NonNull final Map<String, String> map, @NonNull final String... propNames) {
    final Map<String, String> res = new HashMap<>(map);
    for (final String propName : propNames) {
      final String val = getSettingFromEnvironment(propName, null);
      if (val != null) {
        res.put(propName, val);
      }
    }
    return Collections.unmodifiableMap(res);
  }

  /**
   * same as {@link Config#getMapWithPropertiesDefinedByEnvironment(Map, String...)} but using
   * {@code properties} as source of values to overwrite inside map
   *
   * @param map
   * @param properties
   * @param keys
   * @return
   */
  @NonNull
  private static Map<String, String> overwriteKeysFromProperties(
      @NonNull final Map<String, String> map,
      @NonNull final Properties properties,
      @NonNull final String... keys) {
    final Map<String, String> res = new HashMap<>(map);
    for (final String propName : keys) {
      final String val = properties.getProperty(propName, null);
      if (val != null) {
        res.put(propName, val);
      }
    }
    return Collections.unmodifiableMap(res);
  }

  @NonNull
  private static List<String> parseList(final String str) {
    if (str == null || str.trim().isEmpty()) {
      return Collections.emptyList();
    }

    final String[] tokens = str.split(",", -1);
    // Remove whitespace from each item.
    for (int i = 0; i < tokens.length; i++) {
      tokens[i] = tokens[i].trim();
    }
    return Collections.unmodifiableList(Arrays.asList(tokens));
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

  /**
   * Loads the optional configuration properties file into the global {@link Properties} object.
   *
   * @return The {@link Properties} object. the returned instance might be empty of file does not
   *     exist or if it is in a wrong format.
   */
  private static Properties loadConfigurationFile() {
    final Properties properties = new Properties();

    // Reading from system property first and from env after
    String configurationFilePath =
        System.getProperty(propertyNameToSystemPropertyName(CONFIGURATION_FILE));
    if (null == configurationFilePath) {
      configurationFilePath =
          System.getenv(propertyNameToEnvironmentVariableName(CONFIGURATION_FILE));
    }
    if (null == configurationFilePath) {
      return properties;
    }

    // Normalizing tilde (~) paths for unix systems
    configurationFilePath =
        configurationFilePath.replaceFirst("^~", System.getProperty("user.home"));

    // Configuration properties file is optional
    final File configurationFile = new File(configurationFilePath);
    if (!configurationFile.exists()) {
      log.error("Configuration file '{}' not found.", configurationFilePath);
      return properties;
    }

    try (final FileReader fileReader = new FileReader(configurationFile)) {
      properties.load(fileReader);
    } catch (final FileNotFoundException fnf) {
      log.error("Configuration file '{}' not found.", configurationFilePath);
    } catch (final IOException ioe) {
      log.error(
          "Configuration file '{}' cannot be accessed or correctly parsed.", configurationFilePath);
    }

    return properties;
  }

  private static Properties loadCapturedEnvironment() {
    final CapturedEnvironment capturedEnvironment = CapturedEnvironment.get();
    final Properties properties = new Properties();
    for (final Map.Entry<String, String> entry : capturedEnvironment.getProperties().entrySet()) {
      if (entry.getKey() != null && entry.getValue() != null) {
        properties.put(entry.getKey(), entry.getValue());
      }
    }

    return properties;
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
      return new Config(properties, INSTANCE);
    }
  }
}
