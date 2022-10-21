package datadog.trace.api.config;

import static datadog.trace.api.DDTags.SERVICE_TAG;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_HOST;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_PORT;
import static datadog.trace.api.config.GeneralConfig.RUNTIME_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralFeatureConfig.newHashMap;
import static datadog.trace.api.config.JmxFetchConfig.DEFAULT_JMX_FETCH_ENABLED;
import static datadog.trace.api.config.JmxFetchConfig.DEFAULT_JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_ENABLED;
import static datadog.trace.api.config.JmxFetchConfig.DEFAULT_JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_LIMIT;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_CHECK_PERIOD;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_CONFIG;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_CONFIG_DIR;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_ENABLED;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_INITIAL_REFRESH_BEANS_PERIOD;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_METRICS_CONFIGS;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_ENABLED;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_LIMIT;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_REFRESH_BEANS_PERIOD;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_STATSD_HOST;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_STATSD_PORT;
import static datadog.trace.api.config.JmxFetchConfig.JMX_TAGS;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableList;

import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class JmxFetchFeatureConfig extends AbstractFeatureConfig {
  private final GeneralFeatureConfig generalConfig;
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
  private final Map<String, String> jmxTags;

  public JmxFetchFeatureConfig(
      ConfigProvider configProvider,
      GeneralFeatureConfig generalConfig,
      TracerFeatureConfig tracerConfig) {
    super(configProvider);
    this.generalConfig = generalConfig;

    boolean runtimeMetricsEnabled = configProvider.getBoolean(RUNTIME_METRICS_ENABLED, true);
    this.jmxFetchEnabled =
        runtimeMetricsEnabled
            && configProvider.getBoolean(JMX_FETCH_ENABLED, DEFAULT_JMX_FETCH_ENABLED);
    this.jmxFetchConfigDir = configProvider.getString(JMX_FETCH_CONFIG_DIR);
    this.jmxFetchConfigs = tryMakeImmutableList(configProvider.getList(JMX_FETCH_CONFIG));
    this.jmxFetchMetricsConfigs =
        tryMakeImmutableList(configProvider.getList(JMX_FETCH_METRICS_CONFIGS));
    this.jmxFetchCheckPeriod = configProvider.getInteger(JMX_FETCH_CHECK_PERIOD);
    this.jmxFetchInitialRefreshBeansPeriod =
        configProvider.getInteger(JMX_FETCH_INITIAL_REFRESH_BEANS_PERIOD);
    this.jmxFetchRefreshBeansPeriod = configProvider.getInteger(JMX_FETCH_REFRESH_BEANS_PERIOD);

    this.jmxFetchStatsdPort = configProvider.getInteger(JMX_FETCH_STATSD_PORT, DOGSTATSD_PORT);
    this.jmxFetchStatsdHost =
        configProvider.getString(
            JMX_FETCH_STATSD_HOST,
            // default to agent host if an explicit port has been set
            null != this.jmxFetchStatsdPort && this.jmxFetchStatsdPort > 0
                ? tracerConfig.getAgentHost()
                : null,
            DOGSTATSD_HOST);

    this.jmxFetchMultipleRuntimeServicesEnabled =
        configProvider.getBoolean(
            JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_ENABLED,
            DEFAULT_JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_ENABLED);
    this.jmxFetchMultipleRuntimeServicesLimit =
        configProvider.getInteger(
            JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_LIMIT,
            DEFAULT_JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_LIMIT);

    this.jmxTags = configProvider.getMergedMap(JMX_TAGS);
  }

  public boolean isJmxFetchEnabled() {
    return this.jmxFetchEnabled;
  }

  public String getJmxFetchConfigDir() {
    return this.jmxFetchConfigDir;
  }

  public List<String> getJmxFetchConfigs() {
    return this.jmxFetchConfigs;
  }

  public List<String> getJmxFetchMetricsConfigs() {
    return this.jmxFetchMetricsConfigs;
  }

  public Integer getJmxFetchCheckPeriod() {
    return this.jmxFetchCheckPeriod;
  }

  public Integer getJmxFetchRefreshBeansPeriod() {
    return this.jmxFetchRefreshBeansPeriod;
  }

  public Integer getJmxFetchInitialRefreshBeansPeriod() {
    return this.jmxFetchInitialRefreshBeansPeriod;
  }

  public String getJmxFetchStatsdHost() {
    return this.jmxFetchStatsdHost;
  }

  public Integer getJmxFetchStatsdPort() {
    return this.jmxFetchStatsdPort;
  }

  public boolean isJmxFetchMultipleRuntimeServicesEnabled() {
    return this.jmxFetchMultipleRuntimeServicesEnabled;
  }

  public int getJmxFetchMultipleRuntimeServicesLimit() {
    return this.jmxFetchMultipleRuntimeServicesLimit;
  }

  public Map<String, String> getMergedJmxTags() {
    final Map<String, String> runtimeTags = this.generalConfig.getRuntimeTags();
    final Map<String, String> globalTags = this.generalConfig.getGlobalTags();
    final Map<String, String> result =
        newHashMap(
            globalTags.size() + this.jmxTags.size() + runtimeTags.size() + 1 /* for serviceName */);
    result.putAll(globalTags);
    result.putAll(this.jmxTags);
    result.putAll(runtimeTags);
    // service name set here instead of getRuntimeTags because apm already manages the service tag
    // and may choose to override it.
    // Additionally, infra/JMX metrics require `service` rather than APM's `service.name` tag
    result.put(SERVICE_TAG, this.generalConfig.getServiceName());
    return Collections.unmodifiableMap(result);
  }

  @Override
  public String toString() {
    return "JmxFetchFeatureConfig{"
        + "generalConfig="
        + this.generalConfig
        + ", jmxFetchEnabled="
        + this.jmxFetchEnabled
        + ", jmxFetchConfigDir='"
        + this.jmxFetchConfigDir
        + '\''
        + ", jmxFetchConfigs="
        + this.jmxFetchConfigs
        + ", jmxFetchMetricsConfigs="
        + this.jmxFetchMetricsConfigs
        + ", jmxFetchCheckPeriod="
        + this.jmxFetchCheckPeriod
        + ", jmxFetchInitialRefreshBeansPeriod="
        + this.jmxFetchInitialRefreshBeansPeriod
        + ", jmxFetchRefreshBeansPeriod="
        + this.jmxFetchRefreshBeansPeriod
        + ", jmxFetchStatsdHost='"
        + this.jmxFetchStatsdHost
        + '\''
        + ", jmxFetchStatsdPort="
        + this.jmxFetchStatsdPort
        + ", jmxFetchMultipleRuntimeServicesEnabled="
        + this.jmxFetchMultipleRuntimeServicesEnabled
        + ", jmxFetchMultipleRuntimeServicesLimit="
        + this.jmxFetchMultipleRuntimeServicesLimit
        + ", jmxTags="
        + this.jmxTags
        + '}';
  }
}
