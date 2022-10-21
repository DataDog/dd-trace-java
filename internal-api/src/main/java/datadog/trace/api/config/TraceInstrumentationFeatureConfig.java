package datadog.trace.api.config;

import static datadog.trace.api.ConfigDefaults.DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE;
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX;
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE;
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX;
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_GRPC_CLIENT_ERROR_STATUSES;
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_GRPC_SERVER_ERROR_STATUSES;
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_HTTP_CLIENT_SPLIT_BY_DOMAIN;
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_HTTP_CLIENT_TAG_QUERY_STRING;
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_HTTP_SERVER_ROUTE_BASED_NAMING;
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_HTTP_SERVER_TAG_QUERY_STRING;
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_INTEGRATIONS_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_LOGS_INJECTION_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_RESOLVER_OUTLINE_POOL_SIZE;
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_RESOLVER_TYPE_POOL_SIZE;
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_RUNTIME_CONTEXT_FIELD_INJECTION;
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_SERIALVERSIONUID_FIELD_INJECTION;
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_TRACE_ANNOTATIONS;
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_TRACE_EXECUTORS_ALL;
import static datadog.trace.api.config.TraceInstrumentationConfig.DEFAULT_TRACE_METHODS;
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
import static datadog.trace.api.config.TraceInstrumentationConfig.INTEGRATION_SYNAPSE_LEGACY_OPERATION_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.JDBC_CONNECTION_CLASS_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.JDBC_PREPARED_STATEMENT_CLASS_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_QUEUES;
import static datadog.trace.api.config.TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_TOPICS;
import static datadog.trace.api.config.TraceInstrumentationConfig.KAFKA_CLIENT_BASE64_DECODING_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.KAFKA_CLIENT_PROPAGATION_DISABLED_TOPICS;
import static datadog.trace.api.config.TraceInstrumentationConfig.LOGS_INJECTION_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.LOGS_MDC_TAGS_INJECTION_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.MESSAGE_BROKER_SPLIT_BY_DESTINATION;
import static datadog.trace.api.config.TraceInstrumentationConfig.OBFUSCATION_QUERY_STRING_REGEXP;
import static datadog.trace.api.config.TraceInstrumentationConfig.PLAY_REPORT_HTTP_STATUS;
import static datadog.trace.api.config.TraceInstrumentationConfig.RABBIT_PROPAGATION_DISABLED_EXCHANGES;
import static datadog.trace.api.config.TraceInstrumentationConfig.RABBIT_PROPAGATION_DISABLED_QUEUES;
import static datadog.trace.api.config.TraceInstrumentationConfig.RESOLVER_OUTLINE_POOL_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.RESOLVER_OUTLINE_POOL_SIZE;
import static datadog.trace.api.config.TraceInstrumentationConfig.RESOLVER_TYPE_POOL_SIZE;
import static datadog.trace.api.config.TraceInstrumentationConfig.RESOLVER_USE_LOADCLASS;
import static datadog.trace.api.config.TraceInstrumentationConfig.RUNTIME_CONTEXT_FIELD_INJECTION;
import static datadog.trace.api.config.TraceInstrumentationConfig.SERIALVERSIONUID_FIELD_INJECTION;
import static datadog.trace.api.config.TraceInstrumentationConfig.SERVLET_ASYNC_TIMEOUT_ERROR;
import static datadog.trace.api.config.TraceInstrumentationConfig.SERVLET_PRINCIPAL_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.SERVLET_ROOT_CONTEXT_SERVICE_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_ANNOTATIONS;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_CLASSES_EXCLUDE;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_CLASSES_EXCLUDE_FILE;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_CLASSLOADERS_EXCLUDE;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_CODESOURCES_EXCLUDE;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_EXECUTORS;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_EXECUTORS_ALL;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_METHODS;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_THREAD_POOL_EXECUTORS_EXCLUDE;
import static datadog.trace.api.config.TracerConfig.DEFAULT_TRACE_ENABLED;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableList;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableSet;

import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;

public class TraceInstrumentationFeatureConfig extends AbstractFeatureConfig {
  private final StaticConfig staticConfig;
  private final boolean traceEnabled;
  private final boolean integrationSynapseLegacyOperationName;
  private final String traceAnnotations;
  private final boolean logsInjectionEnabled;
  private final boolean logsMDCTagsInjectionEnabled;
  private final String traceMethods;
  private final boolean traceExecutorsAll;
  private final List<String> traceExecutors;
  private final Set<String> traceThreadPoolExecutorsExclude;
  private final boolean httpServerTagQueryString;
  private final boolean httpServerRawQueryString;
  private final boolean httpServerRawResource;
  private final boolean httpServerRouteBasedNaming;
  private final boolean httpClientTagQueryString;
  private final boolean httpClientSplitByDomain;
  private final boolean dbClientSplitByInstance;
  private final boolean dbClientSplitByInstanceTypeSuffix;
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
  private final String obfuscationQueryRegexp;
  // TODO: remove at a future point.
  private final boolean playReportHttpStatus;
  private final boolean servletPrincipalEnabled;
  private final boolean servletAsyncTimeoutError;
  private final String jdbcPreparedStatementClassName;
  private final String jdbcConnectionClassName;
  private final String rootContextServiceName;
  private final Set<String> grpcIgnoredInboundMethods;
  private final Set<String> grpcIgnoredOutboundMethods;
  private final boolean grpcServerTrimPackageResource;
  private final BitSet grpcServerErrorStatuses;
  private final BitSet grpcClientErrorStatuses;

  public TraceInstrumentationFeatureConfig(ConfigProvider configProvider) {
    super(configProvider);
    this.staticConfig = new StaticConfig(configProvider);

    this.traceEnabled = configProvider.getBoolean(TRACE_ENABLED, DEFAULT_TRACE_ENABLED);

    this.integrationSynapseLegacyOperationName =
        configProvider.getBoolean(INTEGRATION_SYNAPSE_LEGACY_OPERATION_NAME, false);

    this.traceAnnotations = configProvider.getString(TRACE_ANNOTATIONS, DEFAULT_TRACE_ANNOTATIONS);

    this.logsInjectionEnabled =
        configProvider.getBoolean(LOGS_INJECTION_ENABLED, DEFAULT_LOGS_INJECTION_ENABLED);
    this.logsMDCTagsInjectionEnabled =
        configProvider.getBoolean(LOGS_MDC_TAGS_INJECTION_ENABLED, true);

    this.traceMethods = configProvider.getString(TRACE_METHODS, DEFAULT_TRACE_METHODS);

    this.traceExecutorsAll =
        configProvider.getBoolean(TRACE_EXECUTORS_ALL, DEFAULT_TRACE_EXECUTORS_ALL);
    this.traceExecutors = tryMakeImmutableList(configProvider.getList(TRACE_EXECUTORS));
    this.traceThreadPoolExecutorsExclude =
        tryMakeImmutableSet(configProvider.getList(TRACE_THREAD_POOL_EXECUTORS_EXCLUDE));

    this.httpServerTagQueryString =
        configProvider.getBoolean(
            HTTP_SERVER_TAG_QUERY_STRING, DEFAULT_HTTP_SERVER_TAG_QUERY_STRING);
    this.httpServerRawQueryString = configProvider.getBoolean(HTTP_SERVER_RAW_QUERY_STRING, true);
    this.httpServerRawResource = configProvider.getBoolean(HTTP_SERVER_RAW_RESOURCE, false);
    this.httpServerRouteBasedNaming =
        configProvider.getBoolean(
            HTTP_SERVER_ROUTE_BASED_NAMING, DEFAULT_HTTP_SERVER_ROUTE_BASED_NAMING);
    this.httpClientTagQueryString =
        configProvider.getBoolean(
            HTTP_CLIENT_TAG_QUERY_STRING, DEFAULT_HTTP_CLIENT_TAG_QUERY_STRING);
    this.httpClientSplitByDomain =
        configProvider.getBoolean(
            HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, DEFAULT_HTTP_CLIENT_SPLIT_BY_DOMAIN);

    this.dbClientSplitByInstance =
        configProvider.getBoolean(
            DB_CLIENT_HOST_SPLIT_BY_INSTANCE, DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE);
    this.dbClientSplitByInstanceTypeSuffix =
        configProvider.getBoolean(
            DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX,
            DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX);

    this.awsPropagationEnabled = isPropagationEnabled(true, "aws");
    this.sqsPropagationEnabled = awsPropagationEnabled && isPropagationEnabled(true, "sqs");

    this.kafkaClientPropagationEnabled = isPropagationEnabled(true, "kafka", "kafka.client");
    this.kafkaClientPropagationDisabledTopics =
        tryMakeImmutableSet(configProvider.getList(KAFKA_CLIENT_PROPAGATION_DISABLED_TOPICS));
    this.kafkaClientBase64DecodingEnabled =
        configProvider.getBoolean(KAFKA_CLIENT_BASE64_DECODING_ENABLED, false);

    this.jmsPropagationEnabled = isPropagationEnabled(true, "jms");
    this.jmsPropagationDisabledTopics =
        tryMakeImmutableSet(configProvider.getList(JMS_PROPAGATION_DISABLED_TOPICS));
    this.jmsPropagationDisabledQueues =
        tryMakeImmutableSet(configProvider.getList(JMS_PROPAGATION_DISABLED_QUEUES));

    this.rabbitPropagationEnabled = isPropagationEnabled(true, "rabbit", "rabbitmq");
    this.rabbitPropagationDisabledQueues =
        tryMakeImmutableSet(configProvider.getList(RABBIT_PROPAGATION_DISABLED_QUEUES));
    this.rabbitPropagationDisabledExchanges =
        tryMakeImmutableSet(configProvider.getList(RABBIT_PROPAGATION_DISABLED_EXCHANGES));

    this.messageBrokerSplitByDestination =
        configProvider.getBoolean(MESSAGE_BROKER_SPLIT_BY_DESTINATION, false);
    this.hystrixTagsEnabled = configProvider.getBoolean(HYSTRIX_TAGS_ENABLED, false);
    this.hystrixMeasuredEnabled = configProvider.getBoolean(HYSTRIX_MEASURED_ENABLED, false);
    this.igniteCacheIncludeKeys = configProvider.getBoolean(IGNITE_CACHE_INCLUDE_KEYS, false);
    this.obfuscationQueryRegexp = configProvider.getString(OBFUSCATION_QUERY_STRING_REGEXP);
    this.playReportHttpStatus = configProvider.getBoolean(PLAY_REPORT_HTTP_STATUS, false);

    this.servletPrincipalEnabled = configProvider.getBoolean(SERVLET_PRINCIPAL_ENABLED, false);
    this.servletAsyncTimeoutError = configProvider.getBoolean(SERVLET_ASYNC_TIMEOUT_ERROR, true);
    this.jdbcPreparedStatementClassName =
        configProvider.getString(JDBC_PREPARED_STATEMENT_CLASS_NAME, "");
    this.jdbcConnectionClassName = configProvider.getString(JDBC_CONNECTION_CLASS_NAME, "");

    this.rootContextServiceName =
        configProvider.getString(
            SERVLET_ROOT_CONTEXT_SERVICE_NAME, DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME);

    this.grpcIgnoredInboundMethods =
        tryMakeImmutableSet(configProvider.getList(GRPC_IGNORED_INBOUND_METHODS));
    this.grpcIgnoredOutboundMethods =
        tryMakeImmutableSet(configProvider.getList(GRPC_IGNORED_OUTBOUND_METHODS));
    this.grpcServerTrimPackageResource =
        configProvider.getBoolean(GRPC_SERVER_TRIM_PACKAGE_RESOURCE, false);
    this.grpcServerErrorStatuses =
        configProvider.getIntegerRange(
            GRPC_SERVER_ERROR_STATUSES, DEFAULT_GRPC_SERVER_ERROR_STATUSES);
    this.grpcClientErrorStatuses =
        configProvider.getIntegerRange(
            GRPC_CLIENT_ERROR_STATUSES, DEFAULT_GRPC_CLIENT_ERROR_STATUSES);
  }

  public boolean isTraceEnabled() {
    return this.traceEnabled;
  }

  public boolean isIntegrationsEnabled() {
    return this.staticConfig.isIntegrationsEnabled();
  }

  public boolean isIntegrationSynapseLegacyOperationName() {
    return this.integrationSynapseLegacyOperationName;
  }

  public String getTraceAnnotations() {
    return this.traceAnnotations;
  }

  public boolean isLogsInjectionEnabled() {
    return this.logsInjectionEnabled;
  }

  public boolean isLogsMDCTagsInjectionEnabled() {
    return this.logsMDCTagsInjectionEnabled;
  }

  public String getTraceMethods() {
    return this.traceMethods;
  }

  public boolean isTraceExecutorsAll() {
    return this.traceExecutorsAll;
  }

  public List<String> getTraceExecutors() {
    return this.traceExecutors;
  }

  public Set<String> getTraceThreadPoolExecutorsExclude() {
    return this.traceThreadPoolExecutorsExclude;
  }

  public List<String> getExcludedClasses() {
    return this.staticConfig.getExcludedClasses();
  }

  public String getExcludedClassesFile() {
    return this.staticConfig.getExcludedClassesFile();
  }

  public Set<String> getExcludedClassLoaders() {
    return this.staticConfig.getExcludedClassLoaders();
  }

  public List<String> getExcludedCodeSources() {
    return this.staticConfig.getExcludedCodeSources();
  }

  public boolean isHttpServerTagQueryString() {
    return this.httpServerTagQueryString;
  }

  public boolean isHttpServerRawQueryString() {
    return this.httpServerRawQueryString;
  }

  public boolean isHttpServerRawResource() {
    return this.httpServerRawResource;
  }

  public boolean isHttpServerRouteBasedNaming() {
    return this.httpServerRouteBasedNaming;
  }

  public boolean isHttpClientTagQueryString() {
    return this.httpClientTagQueryString;
  }

  public boolean isHttpClientSplitByDomain() {
    return this.httpClientSplitByDomain;
  }

  public boolean isDbClientSplitByInstance() {
    return this.dbClientSplitByInstance;
  }

  public boolean isDbClientSplitByInstanceTypeSuffix() {
    return this.dbClientSplitByInstanceTypeSuffix;
  }

  public boolean isAwsPropagationEnabled() {
    return this.awsPropagationEnabled;
  }

  public boolean isSqsPropagationEnabled() {
    return this.sqsPropagationEnabled;
  }

  public boolean isKafkaClientPropagationEnabled() {
    return this.kafkaClientPropagationEnabled;
  }

  public boolean isKafkaClientPropagationDisabledForTopic(String topic) {
    return null != topic && this.kafkaClientPropagationDisabledTopics.contains(topic);
  }

  public boolean isJmsPropagationEnabled() {
    return this.jmsPropagationEnabled;
  }

  public boolean isJmsPropagationDisabledForDestination(final String queueOrTopic) {
    return null != queueOrTopic
        && (this.jmsPropagationDisabledQueues.contains(queueOrTopic)
            || this.jmsPropagationDisabledTopics.contains(queueOrTopic));
  }

  public boolean isKafkaClientBase64DecodingEnabled() {
    return this.kafkaClientBase64DecodingEnabled;
  }

  public boolean isRabbitPropagationEnabled() {
    return this.rabbitPropagationEnabled;
  }

  public boolean isRabbitPropagationDisabledForDestination(final String queueOrExchange) {
    return null != queueOrExchange
        && (this.rabbitPropagationDisabledQueues.contains(queueOrExchange)
            || this.rabbitPropagationDisabledExchanges.contains(queueOrExchange));
  }

  public boolean isMessageBrokerSplitByDestination() {
    return this.messageBrokerSplitByDestination;
  }

  public boolean isHystrixTagsEnabled() {
    return this.hystrixTagsEnabled;
  }

  public boolean isHystrixMeasuredEnabled() {
    return this.hystrixMeasuredEnabled;
  }

  public boolean isIgniteCacheIncludeKeys() {
    return this.igniteCacheIncludeKeys;
  }

  public String getObfuscationQueryRegexp() {
    return this.obfuscationQueryRegexp;
  }

  public boolean getPlayReportHttpStatus() {
    return this.playReportHttpStatus;
  }

  public boolean isServletPrincipalEnabled() {
    return this.servletPrincipalEnabled;
  }

  public boolean isServletAsyncTimeoutError() {
    return this.servletAsyncTimeoutError;
  }

  public String getJdbcPreparedStatementClassName() {
    return this.jdbcPreparedStatementClassName;
  }

  public String getJdbcConnectionClassName() {
    return this.jdbcConnectionClassName;
  }

  public String getRootContextServiceName() {
    return this.rootContextServiceName;
  }

  public boolean isRuntimeContextFieldInjection() {
    return this.staticConfig.isRuntimeContextFieldInjection();
  }

  public boolean isSerialVersionUIDFieldInjection() {
    return this.staticConfig.isSerialVersionUIDFieldInjection();
  }

  public Set<String> getGrpcIgnoredInboundMethods() {
    return this.grpcIgnoredInboundMethods;
  }

  public Set<String> getGrpcIgnoredOutboundMethods() {
    return this.grpcIgnoredOutboundMethods;
  }

  public boolean isGrpcServerTrimPackageResource() {
    return this.grpcServerTrimPackageResource;
  }

  public BitSet getGrpcServerErrorStatuses() {
    return this.grpcServerErrorStatuses;
  }

  public BitSet getGrpcClientErrorStatuses() {
    return this.grpcClientErrorStatuses;
  }

  public boolean isResolverOutlinePoolEnabled() {
    return this.staticConfig.isResolverOutlinePoolEnabled();
  }

  public int getResolverOutlinePoolSize() {
    return this.staticConfig.getResolverOutlinePoolSize();
  }

  public int getResolverTypePoolSize() {
    return this.staticConfig.getResolverTypePoolSize();
  }

  public boolean isResolverUseLoadClassEnabled() {
    return this.staticConfig.isResolverUseLoadClassEnabled();
  }

  public boolean isPropagationEnabled(
      final boolean defaultEnabled, final String... integrationNames) {
    return isEnabled(Arrays.asList(integrationNames), "", ".propagation.enabled", defaultEnabled);
  }

  public boolean isIntegrationEnabled(
      final Iterable<String> integrationNames, final boolean defaultEnabled) {
    return this.staticConfig.isIntegrationEnabled(integrationNames, defaultEnabled);
  }

  public boolean isIntegrationShortcutMatchingEnabled(
      final Iterable<String> integrationNames, final boolean defaultEnabled) {
    return this.staticConfig.isIntegrationShortcutMatchingEnabled(integrationNames, defaultEnabled);
  }

  public boolean isJmxFetchIntegrationEnabled(
      final Iterable<String> integrationNames, final boolean defaultEnabled) {
    return this.staticConfig.isJmxFetchIntegrationEnabled(integrationNames, defaultEnabled);
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

  public boolean isEndToEndDurationEnabled(
      final boolean defaultEnabled, final String... integrationNames) {
    return isEnabled(Arrays.asList(integrationNames), "", ".e2e.duration.enabled", defaultEnabled);
  }

  public boolean isLegacyTracingEnabled(
      final boolean defaultEnabled, final String... integrationNames) {
    return isEnabled(
        Arrays.asList(integrationNames), "", ".legacy.tracing.enabled", defaultEnabled);
  }

  public boolean isTraceAnalyticsIntegrationEnabled(
      final SortedSet<String> integrationNames, final boolean defaultEnabled) {
    return isEnabled(integrationNames, "", ".analytics.enabled", defaultEnabled);
  }

  public boolean isTraceAnalyticsIntegrationEnabled(
      final boolean defaultEnabled, final String... integrationNames) {
    return isEnabled(Arrays.asList(integrationNames), "", ".analytics.enabled", defaultEnabled);
  }

  @Override
  public String toString() {
    return "TraceInstrumentationFeatureConfig{"
        + "staticConfig="
        + this.staticConfig
        + ", traceEnabled="
        + this.traceEnabled
        + ", integrationSynapseLegacyOperationName="
        + this.integrationSynapseLegacyOperationName
        + ", traceAnnotations='"
        + this.traceAnnotations
        + '\''
        + ", logsInjectionEnabled="
        + this.logsInjectionEnabled
        + ", logsMDCTagsInjectionEnabled="
        + this.logsMDCTagsInjectionEnabled
        + ", traceMethods='"
        + this.traceMethods
        + '\''
        + ", traceExecutorsAll="
        + this.traceExecutorsAll
        + ", traceExecutors="
        + this.traceExecutors
        + ", traceThreadPoolExecutorsExclude="
        + this.traceThreadPoolExecutorsExclude
        + ", httpServerTagQueryString="
        + this.httpServerTagQueryString
        + ", httpServerRawQueryString="
        + this.httpServerRawQueryString
        + ", httpServerRawResource="
        + this.httpServerRawResource
        + ", httpServerRouteBasedNaming="
        + this.httpServerRouteBasedNaming
        + ", httpClientTagQueryString="
        + this.httpClientTagQueryString
        + ", httpClientSplitByDomain="
        + this.httpClientSplitByDomain
        + ", dbClientSplitByInstance="
        + this.dbClientSplitByInstance
        + ", dbClientSplitByInstanceTypeSuffix="
        + this.dbClientSplitByInstanceTypeSuffix
        + ", awsPropagationEnabled="
        + this.awsPropagationEnabled
        + ", sqsPropagationEnabled="
        + this.sqsPropagationEnabled
        + ", kafkaClientPropagationEnabled="
        + this.kafkaClientPropagationEnabled
        + ", kafkaClientPropagationDisabledTopics="
        + this.kafkaClientPropagationDisabledTopics
        + ", kafkaClientBase64DecodingEnabled="
        + this.kafkaClientBase64DecodingEnabled
        + ", jmsPropagationEnabled="
        + this.jmsPropagationEnabled
        + ", jmsPropagationDisabledTopics="
        + this.jmsPropagationDisabledTopics
        + ", jmsPropagationDisabledQueues="
        + this.jmsPropagationDisabledQueues
        + ", rabbitPropagationEnabled="
        + this.rabbitPropagationEnabled
        + ", rabbitPropagationDisabledQueues="
        + this.rabbitPropagationDisabledQueues
        + ", rabbitPropagationDisabledExchanges="
        + this.rabbitPropagationDisabledExchanges
        + ", messageBrokerSplitByDestination="
        + this.messageBrokerSplitByDestination
        + ", hystrixTagsEnabled="
        + this.hystrixTagsEnabled
        + ", hystrixMeasuredEnabled="
        + this.hystrixMeasuredEnabled
        + ", igniteCacheIncludeKeys="
        + this.igniteCacheIncludeKeys
        + ", obfuscationQueryRegexp='"
        + this.obfuscationQueryRegexp
        + '\''
        + ", playReportHttpStatus="
        + this.playReportHttpStatus
        + ", servletPrincipalEnabled="
        + this.servletPrincipalEnabled
        + ", servletAsyncTimeoutError="
        + this.servletAsyncTimeoutError
        + ", jdbcPreparedStatementClassName='"
        + this.jdbcPreparedStatementClassName
        + '\''
        + ", jdbcConnectionClassName='"
        + this.jdbcConnectionClassName
        + '\''
        + ", rootContextServiceName='"
        + this.rootContextServiceName
        + '\''
        + ", grpcIgnoredInboundMethods="
        + this.grpcIgnoredInboundMethods
        + ", grpcIgnoredOutboundMethods="
        + this.grpcIgnoredOutboundMethods
        + ", grpcServerTrimPackageResource="
        + this.grpcServerTrimPackageResource
        + ", grpcServerErrorStatuses="
        + this.grpcServerErrorStatuses
        + ", grpcClientErrorStatuses="
        + this.grpcClientErrorStatuses
        + '}';
  }

  public static class StaticConfig extends AbstractFeatureConfig {
    private final boolean integrationsEnabled;
    private final List<String> excludedClasses;
    private final String excludedClassesFile;
    private final Set<String> excludedClassLoaders;
    private final List<String> excludedCodeSources;
    private final boolean runtimeContextFieldInjection;
    private final boolean serialVersionUIDFieldInjection;
    private final boolean resolverOutlinePoolEnabled;
    private final int resolverOutlinePoolSize;
    private final int resolverTypePoolSize;
    private final boolean resolverUseLoadClassEnabled;

    /**
     * This configuration is frozen at application initialization and cannot be updated at a later
     * time by user configuration.
     *
     * @param configProvider The configuration provider to get configuration value from.
     */
    public StaticConfig(ConfigProvider configProvider) {
      super(configProvider);
      this.integrationsEnabled =
          configProvider.getBoolean(INTEGRATIONS_ENABLED, DEFAULT_INTEGRATIONS_ENABLED);

      this.excludedClasses = tryMakeImmutableList(configProvider.getList(TRACE_CLASSES_EXCLUDE));
      this.excludedClassesFile = configProvider.getString(TRACE_CLASSES_EXCLUDE_FILE);
      this.excludedClassLoaders =
          tryMakeImmutableSet(configProvider.getList(TRACE_CLASSLOADERS_EXCLUDE));
      this.excludedCodeSources =
          tryMakeImmutableList(configProvider.getList(TRACE_CODESOURCES_EXCLUDE));

      this.runtimeContextFieldInjection =
          configProvider.getBoolean(
              RUNTIME_CONTEXT_FIELD_INJECTION, DEFAULT_RUNTIME_CONTEXT_FIELD_INJECTION);
      this.serialVersionUIDFieldInjection =
          configProvider.getBoolean(
              SERIALVERSIONUID_FIELD_INJECTION, DEFAULT_SERIALVERSIONUID_FIELD_INJECTION);

      this.resolverOutlinePoolEnabled =
          configProvider.getBoolean(RESOLVER_OUTLINE_POOL_ENABLED, true);
      this.resolverOutlinePoolSize =
          configProvider.getInteger(RESOLVER_OUTLINE_POOL_SIZE, DEFAULT_RESOLVER_OUTLINE_POOL_SIZE);
      this.resolverTypePoolSize =
          configProvider.getInteger(RESOLVER_TYPE_POOL_SIZE, DEFAULT_RESOLVER_TYPE_POOL_SIZE);
      this.resolverUseLoadClassEnabled = configProvider.getBoolean(RESOLVER_USE_LOADCLASS, true);
    }

    public static StaticConfig getInstance() {
      return Singleton.INSTANCE;
    }

    public boolean isIntegrationsEnabled() {
      return this.integrationsEnabled;
    }

    public List<String> getExcludedClasses() {
      return this.excludedClasses;
    }

    public String getExcludedClassesFile() {
      return this.excludedClassesFile;
    }

    public Set<String> getExcludedClassLoaders() {
      return this.excludedClassLoaders;
    }

    public List<String> getExcludedCodeSources() {
      return this.excludedCodeSources;
    }

    public boolean isRuntimeContextFieldInjection() {
      return this.runtimeContextFieldInjection;
    }

    public boolean isSerialVersionUIDFieldInjection() {
      return this.serialVersionUIDFieldInjection;
    }

    public boolean isResolverOutlinePoolEnabled() {
      return this.resolverOutlinePoolEnabled;
    }

    public int getResolverOutlinePoolSize() {
      return this.resolverOutlinePoolSize;
    }

    public int getResolverTypePoolSize() {
      return this.resolverTypePoolSize;
    }

    public boolean isResolverUseLoadClassEnabled() {
      return this.resolverUseLoadClassEnabled;
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

    @Override
    public String toString() {
      return "StaticConfig{"
          + "integrationsEnabled="
          + this.integrationsEnabled
          + ", excludedClasses="
          + this.excludedClasses
          + ", excludedClassesFile='"
          + this.excludedClassesFile
          + '\''
          + ", excludedClassLoaders="
          + this.excludedClassLoaders
          + ", excludedCodeSources="
          + this.excludedCodeSources
          + ", runtimeContextFieldInjection="
          + this.runtimeContextFieldInjection
          + ", serialVersionUIDFieldInjection="
          + this.serialVersionUIDFieldInjection
          + ", resolverOutlinePoolEnabled="
          + this.resolverOutlinePoolEnabled
          + ", resolverOutlinePoolSize="
          + this.resolverOutlinePoolSize
          + ", resolverTypePoolSize="
          + this.resolverTypePoolSize
          + ", resolverUseLoadClassEnabled="
          + this.resolverUseLoadClassEnabled
          + '}';
    }

    private static class Singleton {
      private static final StaticConfig INSTANCE = new StaticConfig(ConfigProvider.getInstance());
    }
  }
}
