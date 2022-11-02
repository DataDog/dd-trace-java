package datadog.trace.api.config;

import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_HOST;
import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_TIMEOUT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_AGENT_PORT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_X_DATADOG_TAGS_MAX_LENGTH;
import static datadog.trace.api.IdGenerationStrategy.RANDOM;
import static datadog.trace.api.config.AppSecConfig.APPSEC_IP_ADDR_HEADER;
import static datadog.trace.api.config.TracerConfig.AGENT_HOST;
import static datadog.trace.api.config.TracerConfig.AGENT_NAMED_PIPE;
import static datadog.trace.api.config.TracerConfig.AGENT_PORT_LEGACY;
import static datadog.trace.api.config.TracerConfig.AGENT_TIMEOUT;
import static datadog.trace.api.config.TracerConfig.AGENT_UNIX_DOMAIN_SOCKET;
import static datadog.trace.api.config.TracerConfig.CLIENT_IP_ENABLED;
import static datadog.trace.api.config.TracerConfig.CLOCK_SYNC_PERIOD;
import static datadog.trace.api.config.TracerConfig.DEFAULT_AGENT_WRITER_TYPE;
import static datadog.trace.api.config.TracerConfig.DEFAULT_ANALYTICS_SAMPLE_RATE;
import static datadog.trace.api.config.TracerConfig.DEFAULT_CLIENT_IP_ENABLED;
import static datadog.trace.api.config.TracerConfig.DEFAULT_CLOCK_SYNC_PERIOD;
import static datadog.trace.api.config.TracerConfig.DEFAULT_HTTP_CLIENT_ERROR_STATUSES;
import static datadog.trace.api.config.TracerConfig.DEFAULT_HTTP_SERVER_ERROR_STATUSES;
import static datadog.trace.api.config.TracerConfig.DEFAULT_PARTIAL_FLUSH_MIN_SPANS;
import static datadog.trace.api.config.TracerConfig.DEFAULT_PRIORITY_SAMPLING_ENABLED;
import static datadog.trace.api.config.TracerConfig.DEFAULT_PRIORITY_SAMPLING_FORCE;
import static datadog.trace.api.config.TracerConfig.DEFAULT_PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED;
import static datadog.trace.api.config.TracerConfig.DEFAULT_PROPAGATION_STYLE_EXTRACT;
import static datadog.trace.api.config.TracerConfig.DEFAULT_PROPAGATION_STYLE_INJECT;
import static datadog.trace.api.config.TracerConfig.DEFAULT_SCOPE_DEPTH_LIMIT;
import static datadog.trace.api.config.TracerConfig.DEFAULT_SCOPE_ITERATION_KEEP_ALIVE;
import static datadog.trace.api.config.TracerConfig.DEFAULT_TRACE_AGENT_V05_ENABLED;
import static datadog.trace.api.config.TracerConfig.DEFAULT_TRACE_ANALYTICS_ENABLED;
import static datadog.trace.api.config.TracerConfig.DEFAULT_TRACE_RATE_LIMIT;
import static datadog.trace.api.config.TracerConfig.DEFAULT_TRACE_RESOLVER_ENABLED;
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
import static datadog.trace.api.config.TracerConfig.SPLIT_BY_TAGS;
import static datadog.trace.api.config.TracerConfig.TRACE_AGENT_ARGS;
import static datadog.trace.api.config.TracerConfig.TRACE_AGENT_PATH;
import static datadog.trace.api.config.TracerConfig.TRACE_AGENT_PORT;
import static datadog.trace.api.config.TracerConfig.TRACE_AGENT_URL;
import static datadog.trace.api.config.TracerConfig.TRACE_ANALYTICS_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_CLIENT_IP_HEADER;
import static datadog.trace.api.config.TracerConfig.TRACE_CLIENT_IP_RESOLVER_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING;
import static datadog.trace.api.config.TracerConfig.TRACE_RATE_LIMIT;
import static datadog.trace.api.config.TracerConfig.TRACE_RESOLVER_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_OPERATION_RULES;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_RULES;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_SERVICE_RULES;
import static datadog.trace.api.config.TracerConfig.TRACE_STRICT_WRITES_ENABLED;
import static datadog.trace.api.config.TracerConfig.TRACE_X_DATADOG_TAGS_MAX_LENGTH;
import static datadog.trace.api.config.TracerConfig.WRITER_TYPE;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableSet;

import datadog.trace.api.IdGenerationStrategy;
import datadog.trace.api.PropagationStyle;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * System properties are {@link TracerFeatureConfig#PREFIX}'ed. Environment variables are the same
 * as the system property, but uppercased and '.' is replaced with '_'.
 */
public class TracerFeatureConfig extends AbstractFeatureConfig {
  private static final Logger LOGGER = LoggerFactory.getLogger(TracerFeatureConfig.class);
  private static final String PREFIX = "dd.";

  private final IdGenerationStrategy idGenerationStrategy;
  private final String writerType;
  private final AgentConfig agentConfig;
  private final Set<String> noProxyHosts;
  private final String traceAgentPath;
  private final List<String> traceAgentArgs;

  private final boolean prioritySamplingEnabled;
  private final String prioritySamplingForce;

  private final boolean traceResolverEnabled;
  private final Map<String, String> serviceMapping;
  private final boolean traceAnalyticsEnabled;
  private final String traceClientIpHeader;
  private final boolean traceClientIpResolverEnabled;
  private final boolean clientIpEnabled;
  private final Map<String, String> traceSamplingServiceRules;
  private final Map<String, String> traceSamplingOperationRules;
  private final String traceSamplingRules;
  private final Double traceSampleRate;
  private final int traceRateLimit;
  private final Map<String, String> requestHeaderTags;
  private final Map<String, String> responseHeaderTags;
  private final BitSet httpServerErrorStatuses;
  private final BitSet httpClientErrorStatuses;
  private final Map<String, String> httpServerPathResourceNameMapping;
  private final Set<String> splitByTags;
  private final int scopeDepthLimit;
  private final boolean scopeStrictMode;
  private final boolean scopeInheritAsyncPropagation;
  private final int scopeIterationKeepAlive;
  private final int partialFlushMinSpans;
  private final boolean traceStrictWritesEnabled;
  private final boolean logExtractHeaderNames;
  private final Set<PropagationStyle> propagationStylesToExtract;
  private final Set<PropagationStyle> propagationStylesToInject;
  private final boolean traceAgentV05Enabled;
  private final int clockSyncPeriod;
  private final int xDatadogTagsMaxLength;

  public TracerFeatureConfig(ConfigProvider configProvider) {
    super(configProvider);
    this.idGenerationStrategy =
        configProvider.getEnum(ID_GENERATION_STRATEGY, IdGenerationStrategy.class, RANDOM);
    if (idGenerationStrategy != RANDOM) {
      LOGGER.warn(
          "*** you are using an unsupported id generation strategy {} - this can impact correctness of traces",
          idGenerationStrategy);
    }
    this.writerType = configProvider.getString(WRITER_TYPE, DEFAULT_AGENT_WRITER_TYPE);
    this.agentConfig = new AgentConfig(configProvider);

    // DD_PROXY_NO_PROXY is specified as a space-separated list of hosts
    this.noProxyHosts = tryMakeImmutableSet(configProvider.getSpacedList(PROXY_NO_PROXY));

    this.traceAgentPath = configProvider.getString(TRACE_AGENT_PATH);
    String traceAgentArgsString = configProvider.getString(TRACE_AGENT_ARGS);
    if (traceAgentArgsString == null) {
      this.traceAgentArgs = Collections.emptyList();
    } else {
      this.traceAgentArgs =
          Collections.unmodifiableList(
              new ArrayList<>(parseStringIntoSetOfNonEmptyStrings(traceAgentArgsString)));
    }

    this.prioritySamplingEnabled =
        configProvider.getBoolean(PRIORITY_SAMPLING, DEFAULT_PRIORITY_SAMPLING_ENABLED);
    this.prioritySamplingForce =
        configProvider.getString(PRIORITY_SAMPLING_FORCE, DEFAULT_PRIORITY_SAMPLING_FORCE);

    this.traceResolverEnabled =
        configProvider.getBoolean(TRACE_RESOLVER_ENABLED, DEFAULT_TRACE_RESOLVER_ENABLED);
    this.serviceMapping = configProvider.getMergedMap(SERVICE_MAPPING);

    this.traceAnalyticsEnabled =
        configProvider.getBoolean(TRACE_ANALYTICS_ENABLED, DEFAULT_TRACE_ANALYTICS_ENABLED);
    String traceClientIpHeader = configProvider.getString(TRACE_CLIENT_IP_HEADER);
    if (traceClientIpHeader == null) {
      traceClientIpHeader = configProvider.getString(APPSEC_IP_ADDR_HEADER);
    }
    if (traceClientIpHeader != null) {
      traceClientIpHeader = traceClientIpHeader.toLowerCase(Locale.ROOT);
    }
    this.traceClientIpHeader = traceClientIpHeader;
    this.traceClientIpResolverEnabled =
        configProvider.getBoolean(TRACE_CLIENT_IP_RESOLVER_ENABLED, true);
    this.clientIpEnabled = configProvider.getBoolean(CLIENT_IP_ENABLED, DEFAULT_CLIENT_IP_ENABLED);

    this.traceSamplingServiceRules = configProvider.getMergedMap(TRACE_SAMPLING_SERVICE_RULES);
    this.traceSamplingOperationRules = configProvider.getMergedMap(TRACE_SAMPLING_OPERATION_RULES);
    this.traceSamplingRules = configProvider.getString(TRACE_SAMPLING_RULES);
    this.traceSampleRate = configProvider.getDouble(TRACE_SAMPLE_RATE);
    this.traceRateLimit = configProvider.getInteger(TRACE_RATE_LIMIT, DEFAULT_TRACE_RATE_LIMIT);
    if (isEnabled(false, HEADER_TAGS, ".legacy.parsing.enabled")) {
      this.requestHeaderTags = configProvider.getMergedMap(HEADER_TAGS);
      this.responseHeaderTags = Collections.emptyMap();
      if (configProvider.isSet(REQUEST_HEADER_TAGS)) {
        logIgnoredSettingWarning(REQUEST_HEADER_TAGS, HEADER_TAGS, ".legacy.parsing.enabled");
      }
      if (configProvider.isSet(RESPONSE_HEADER_TAGS)) {
        logIgnoredSettingWarning(RESPONSE_HEADER_TAGS, HEADER_TAGS, ".legacy.parsing.enabled");
      }
    } else {
      this.requestHeaderTags =
          configProvider.getMergedMapWithOptionalMappings(
              "http.request.headers.", true, HEADER_TAGS, REQUEST_HEADER_TAGS);
      this.responseHeaderTags =
          configProvider.getMergedMapWithOptionalMappings(
              "http.response.headers.", true, HEADER_TAGS, RESPONSE_HEADER_TAGS);
    }
    this.httpServerPathResourceNameMapping =
        configProvider.getOrderedMap(TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING);
    this.httpServerErrorStatuses =
        configProvider.getIntegerRange(
            HTTP_SERVER_ERROR_STATUSES, DEFAULT_HTTP_SERVER_ERROR_STATUSES);
    this.httpClientErrorStatuses =
        configProvider.getIntegerRange(
            HTTP_CLIENT_ERROR_STATUSES, DEFAULT_HTTP_CLIENT_ERROR_STATUSES);
    this.splitByTags = tryMakeImmutableSet(configProvider.getList(SPLIT_BY_TAGS));

    this.scopeDepthLimit = configProvider.getInteger(SCOPE_DEPTH_LIMIT, DEFAULT_SCOPE_DEPTH_LIMIT);
    this.scopeStrictMode = configProvider.getBoolean(SCOPE_STRICT_MODE, false);
    this.scopeInheritAsyncPropagation =
        configProvider.getBoolean(SCOPE_INHERIT_ASYNC_PROPAGATION, true);
    this.scopeIterationKeepAlive =
        configProvider.getInteger(SCOPE_ITERATION_KEEP_ALIVE, DEFAULT_SCOPE_ITERATION_KEEP_ALIVE);

    this.partialFlushMinSpans =
        configProvider.getInteger(PARTIAL_FLUSH_MIN_SPANS, DEFAULT_PARTIAL_FLUSH_MIN_SPANS);
    this.traceStrictWritesEnabled = configProvider.getBoolean(TRACE_STRICT_WRITES_ENABLED, false);

    this.logExtractHeaderNames =
        configProvider.getBoolean(
            PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED,
            DEFAULT_PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED);
    this.propagationStylesToExtract =
        getPropagationStyleSetSettingFromEnvironmentOrDefault(
            PROPAGATION_STYLE_EXTRACT, DEFAULT_PROPAGATION_STYLE_EXTRACT);
    this.propagationStylesToInject =
        getPropagationStyleSetSettingFromEnvironmentOrDefault(
            PROPAGATION_STYLE_INJECT, DEFAULT_PROPAGATION_STYLE_INJECT);
    this.traceAgentV05Enabled =
        configProvider.getBoolean(ENABLE_TRACE_AGENT_V05, DEFAULT_TRACE_AGENT_V05_ENABLED);
    this.clockSyncPeriod = configProvider.getInteger(CLOCK_SYNC_PERIOD, DEFAULT_CLOCK_SYNC_PERIOD);
    this.xDatadogTagsMaxLength =
        configProvider.getInteger(
            TRACE_X_DATADOG_TAGS_MAX_LENGTH, DEFAULT_TRACE_X_DATADOG_TAGS_MAX_LENGTH);
  }

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
  private static Set<PropagationStyle> convertStringSetToPropagationStyleSet(
      final Set<String> input) {
    // Using LinkedHashSet to preserve original string order
    final Set<PropagationStyle> result = new LinkedHashSet<>();
    for (final String value : input) {
      try {
        result.add(PropagationStyle.valueOf(value.toUpperCase()));
      } catch (final IllegalArgumentException e) {
        LOGGER.debug("Cannot recognize config string value: {}, {}", value, PropagationStyle.class);
      }
    }
    return Collections.unmodifiableSet(result);
  }

  private void logIgnoredSettingWarning(
      String setting, String overridingSetting, String overridingSuffix) {
    LOGGER.warn(
        "Setting {} ignored since {}{} is enabled.",
        propertyNameToSystemPropertyName(setting),
        propertyNameToSystemPropertyName(overridingSetting),
        overridingSuffix);
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

  public IdGenerationStrategy getIdGenerationStrategy() {
    return this.idGenerationStrategy;
  }

  public String getWriterType() {
    return this.writerType;
  }

  public boolean isAgentConfiguredUsingDefault() {
    return this.agentConfig.configuredUsingDefault;
  }

  public String getAgentUrl() {
    return this.agentConfig.url;
  }

  public String getAgentHost() {
    return this.agentConfig.host;
  }

  public int getAgentPort() {
    return this.agentConfig.port;
  }

  public String getAgentUnixDomainSocket() {
    return this.agentConfig.unixDomainSocket;
  }

  public String getAgentNamedPipe() {
    return this.agentConfig.namedPipe;
  }

  /**
   * Get the agent timeout.
   *
   * @return The agent timeout (in seconds).
   */
  public int getAgentTimeout() {
    return this.agentConfig.timeout;
  }

  public Set<String> getNoProxyHosts() {
    return this.noProxyHosts;
  }

  public String getTraceAgentPath() {
    return this.traceAgentPath;
  }

  public List<String> getTraceAgentArgs() {
    return this.traceAgentArgs;
  }

  public boolean isPrioritySamplingEnabled() {
    return this.prioritySamplingEnabled;
  }

  public String getPrioritySamplingForce() {
    return this.prioritySamplingForce;
  }

  public boolean isTraceResolverEnabled() {
    return this.traceResolverEnabled;
  }

  public Map<String, String> getServiceMapping() {
    return this.serviceMapping;
  }

  public boolean isTraceAnalyticsEnabled() {
    return this.traceAnalyticsEnabled;
  }

  public String getTraceClientIpHeader() {
    return this.traceClientIpHeader;
  }

  public boolean isTraceClientIpResolverEnabled() {
    return this.traceClientIpResolverEnabled;
  }

  public boolean isClientIpEnabled() {
    return this.clientIpEnabled;
  }

  public Map<String, String> getTraceSamplingServiceRules() {
    return this.traceSamplingServiceRules;
  }

  public Map<String, String> getTraceSamplingOperationRules() {
    return this.traceSamplingOperationRules;
  }

  public String getTraceSamplingRules() {
    return this.traceSamplingRules;
  }

  public Double getTraceSampleRate() {
    return this.traceSampleRate;
  }

  public int getTraceRateLimit() {
    return this.traceRateLimit;
  }

  public Map<String, String> getRequestHeaderTags() {
    return this.requestHeaderTags;
  }

  public Map<String, String> getResponseHeaderTags() {
    return this.responseHeaderTags;
  }

  public Map<String, String> getHttpServerPathResourceNameMapping() {
    return this.httpServerPathResourceNameMapping;
  }

  public BitSet getHttpServerErrorStatuses() {
    return this.httpServerErrorStatuses;
  }

  public BitSet getHttpClientErrorStatuses() {
    return this.httpClientErrorStatuses;
  }

  public Set<String> getSplitByTags() {
    return this.splitByTags;
  }

  public int getScopeDepthLimit() {
    return this.scopeDepthLimit;
  }

  public boolean isScopeStrictMode() {
    return this.scopeStrictMode;
  }

  public boolean isScopeInheritAsyncPropagation() {
    return this.scopeInheritAsyncPropagation;
  }

  public int getScopeIterationKeepAlive() {
    return this.scopeIterationKeepAlive;
  }

  public int getPartialFlushMinSpans() {
    return this.partialFlushMinSpans;
  }

  public boolean isTraceStrictWritesEnabled() {
    return this.traceStrictWritesEnabled;
  }

  public boolean isLogExtractHeaderNames() {
    return this.logExtractHeaderNames;
  }

  public Set<PropagationStyle> getPropagationStylesToExtract() {
    return this.propagationStylesToExtract;
  }

  public Set<PropagationStyle> getPropagationStylesToInject() {
    return this.propagationStylesToInject;
  }

  public boolean isSamplingMechanismValidationDisabled() {
    return this.configProvider.getBoolean(
        TracerConfig.SAMPLING_MECHANISM_VALIDATION_DISABLED, false);
  }

  /**
   * Returns the sample rate for the specified instrumentation or {@link
   * TracerConfig#DEFAULT_ANALYTICS_SAMPLE_RATE} if none specified.
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

  public boolean isTraceAgentV05Enabled() {
    return this.traceAgentV05Enabled;
  }

  public int getClockSyncPeriod() {
    return this.clockSyncPeriod;
  }

  public int getxDatadogTagsMaxLength() {
    return this.xDatadogTagsMaxLength;
  }

  private static class AgentConfig {
    private final boolean configuredUsingDefault;
    private final String url;
    private final String host;
    private final int port;
    private final String unixDomainSocket;
    private final String namedPipe;
    /** The agent timeout (in seconds). */
    private final int timeout;

    private AgentConfig(ConfigProvider configProvider) {
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
          LOGGER.warn("{} not configured correctly: {}. Ignoring", TRACE_AGENT_URL, e.getMessage());
        }
      }

      if (agentHostFromEnvironment == null) {
        agentHostFromEnvironment = configProvider.getString(AGENT_HOST);
        rebuildAgentUrl = true;
      }

      if (agentPortFromEnvironment < 0) {
        agentPortFromEnvironment =
            configProvider.getInteger(TRACE_AGENT_PORT, -1, AGENT_PORT_LEGACY);
        rebuildAgentUrl = true;
      }

      if (agentHostFromEnvironment == null) {
        this.host = DEFAULT_AGENT_HOST;
      } else {
        this.host = agentHostFromEnvironment;
      }

      if (agentPortFromEnvironment < 0) {
        this.port = DEFAULT_TRACE_AGENT_PORT;
      } else {
        this.port = agentPortFromEnvironment;
      }

      if (rebuildAgentUrl) {
        this.url = "http://" + this.host + ":" + this.port;
      } else {
        this.url = agentUrlFromEnvironment;
      }

      if (unixSocketFromEnvironment == null) {
        unixSocketFromEnvironment = configProvider.getString(AGENT_UNIX_DOMAIN_SOCKET);
        String unixPrefix = "unix://";
        // handle situation where someone passes us a unix:// URL instead of a socket path
        if (unixSocketFromEnvironment != null && unixSocketFromEnvironment.startsWith(unixPrefix)) {
          unixSocketFromEnvironment = unixSocketFromEnvironment.substring(unixPrefix.length());
        }
      }

      this.unixDomainSocket = unixSocketFromEnvironment;

      this.namedPipe = configProvider.getString(AGENT_NAMED_PIPE);

      this.configuredUsingDefault =
          agentHostFromEnvironment == null
              && agentPortFromEnvironment < 0
              && unixSocketFromEnvironment == null
              && this.namedPipe == null;

      this.timeout = configProvider.getInteger(AGENT_TIMEOUT, DEFAULT_AGENT_TIMEOUT);
    }

    @Override
    public String toString() {
      return "AgentConfig{"
          + "configuredUsingDefault="
          + this.configuredUsingDefault
          + ", url='"
          + this.url
          + '\''
          + ", host='"
          + this.host
          + '\''
          + ", port="
          + this.port
          + ", unixDomainSocket='"
          + this.unixDomainSocket
          + '\''
          + ", namedPipe='"
          + this.namedPipe
          + '\''
          + ", timeout="
          + this.timeout
          + '}';
    }
  }

  @Override
  public String toString() {
    return "TracerFeatureConfig{"
        + "idGenerationStrategy="
        + this.idGenerationStrategy
        + ", writerType='"
        + this.writerType
        + '\''
        + ", agentConfig="
        + this.agentConfig
        + ", noProxyHosts="
        + this.noProxyHosts
        + ", traceAgentPath='"
        + this.traceAgentPath
        + '\''
        + ", traceAgentArgs="
        + this.traceAgentArgs
        + ", prioritySamplingEnabled="
        + this.prioritySamplingEnabled
        + ", prioritySamplingForce='"
        + this.prioritySamplingForce
        + '\''
        + ", traceResolverEnabled="
        + this.traceResolverEnabled
        + ", serviceMapping="
        + this.serviceMapping
        + ", traceAnalyticsEnabled="
        + this.traceAnalyticsEnabled
        + ", traceClientIpHeader='"
        + this.traceClientIpHeader
        + '\''
        + ", traceClientIpResolverEnabled="
        + this.traceClientIpResolverEnabled
        + ", clientIpEnabled="
        + this.clientIpEnabled
        + ", traceSamplingServiceRules="
        + this.traceSamplingServiceRules
        + ", traceSamplingOperationRules="
        + this.traceSamplingOperationRules
        + ", traceSamplingRules='"
        + this.traceSamplingRules
        + '\''
        + ", traceSampleRate="
        + this.traceSampleRate
        + ", traceRateLimit="
        + this.traceRateLimit
        + ", requestHeaderTags="
        + this.requestHeaderTags
        + ", responseHeaderTags="
        + this.responseHeaderTags
        + ", httpServerErrorStatuses="
        + this.httpServerErrorStatuses
        + ", httpClientErrorStatuses="
        + this.httpClientErrorStatuses
        + ", httpServerPathResourceNameMapping="
        + this.httpServerPathResourceNameMapping
        + ", splitByTags="
        + this.splitByTags
        + ", scopeDepthLimit="
        + this.scopeDepthLimit
        + ", scopeStrictMode="
        + this.scopeStrictMode
        + ", scopeInheritAsyncPropagation="
        + this.scopeInheritAsyncPropagation
        + ", scopeIterationKeepAlive="
        + this.scopeIterationKeepAlive
        + ", partialFlushMinSpans="
        + this.partialFlushMinSpans
        + ", traceStrictWritesEnabled="
        + this.traceStrictWritesEnabled
        + ", logExtractHeaderNames="
        + this.logExtractHeaderNames
        + ", propagationStylesToExtract="
        + this.propagationStylesToExtract
        + ", propagationStylesToInject="
        + this.propagationStylesToInject
        + ", traceAgentV05Enabled="
        + this.traceAgentV05Enabled
        + ", clockSyncPeriod="
        + this.clockSyncPeriod
        + ", xDatadogTagsMaxLength="
        + this.xDatadogTagsMaxLength
        + '}';
  }
}
