package datadog.trace.api.config;

import static datadog.trace.api.ConfigDefaults.DEFAULT_SERVICE_NAME;
import static datadog.trace.api.DDTags.INTERNAL_HOST_NAME;
import static datadog.trace.api.DDTags.LANGUAGE_TAG_KEY;
import static datadog.trace.api.DDTags.LANGUAGE_TAG_VALUE;
import static datadog.trace.api.DDTags.RUNTIME_ID_TAG;
import static datadog.trace.api.DDTags.SERVICE;
import static datadog.trace.api.Platform.isJavaVersionAtLeast;
import static datadog.trace.api.config.AbstractFeatureConfig.parseStringIntoSetOfNonEmptyStrings;
import static datadog.trace.api.config.GeneralConfig.API_KEY;
import static datadog.trace.api.config.GeneralConfig.API_KEY_FILE;
import static datadog.trace.api.config.GeneralConfig.AZURE_APP_SERVICES;
import static datadog.trace.api.config.GeneralConfig.DATA_STREAMS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.DEFAULT_DATA_STREAMS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.DEFAULT_DOGSTATSD_START_DELAY;
import static datadog.trace.api.config.GeneralConfig.DEFAULT_HEALTH_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.DEFAULT_PERF_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.DEFAULT_SITE;
import static datadog.trace.api.config.GeneralConfig.DEFAULT_TELEMETRY_ENABLED;
import static datadog.trace.api.config.GeneralConfig.DEFAULT_TELEMETRY_HEARTBEAT_INTERVAL;
import static datadog.trace.api.config.GeneralConfig.DEFAULT_TRACE_REPORT_HOSTNAME;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_ARGS;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_NAMED_PIPE;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_PATH;
import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_START_DELAY;
import static datadog.trace.api.config.GeneralConfig.ENV;
import static datadog.trace.api.config.GeneralConfig.GLOBAL_TAGS;
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_STATSD_HOST;
import static datadog.trace.api.config.GeneralConfig.HEALTH_METRICS_STATSD_PORT;
import static datadog.trace.api.config.GeneralConfig.INTERNAL_EXIT_ON_FAILURE;
import static datadog.trace.api.config.GeneralConfig.PERF_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.PRIMARY_TAG;
import static datadog.trace.api.config.GeneralConfig.RUNTIME_ID_ENABLED;
import static datadog.trace.api.config.GeneralConfig.RUNTIME_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.SERVICE_NAME;
import static datadog.trace.api.config.GeneralConfig.SITE;
import static datadog.trace.api.config.GeneralConfig.TAGS;
import static datadog.trace.api.config.GeneralConfig.TELEMETRY_ENABLED;
import static datadog.trace.api.config.GeneralConfig.TELEMETRY_HEARTBEAT_INTERVAL;
import static datadog.trace.api.config.GeneralConfig.TRACER_METRICS_BUFFERING_ENABLED;
import static datadog.trace.api.config.GeneralConfig.TRACER_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.TRACER_METRICS_MAX_AGGREGATES;
import static datadog.trace.api.config.GeneralConfig.TRACER_METRICS_MAX_PENDING;
import static datadog.trace.api.config.GeneralConfig.VERSION;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_START_DELAY;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_API_KEY_FILE_OLD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_API_KEY_FILE_VERY_OLD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_API_KEY_OLD;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_API_KEY_VERY_OLD;
import static datadog.trace.api.config.TracerConfig.SPAN_TAGS;
import static datadog.trace.api.config.TracerConfig.TRACE_REPORT_HOSTNAME;
import static datadog.trace.util.Strings.propertyNameToEnvironmentVariableName;
import static datadog.trace.util.Strings.toEnvVar;

import datadog.trace.api.Config;
import datadog.trace.api.ConfigCollector;
import datadog.trace.api.WellKnownTags;
import datadog.trace.bootstrap.config.provider.CapturedEnvironmentConfigSource;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.config.provider.SystemPropertiesConfigSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GeneralFeatureConfig {
  private static final Logger LOGGER = LoggerFactory.getLogger(GeneralFeatureConfig.class);

  private final long startTimeMillis;
  /**
   * this is a random UUID that gets generated on JVM start up and is attached to every root span
   * and every JMX metric that is sent out.
   */
  private final String runtimeId;
  /** This is the version of the runtime, ex: 1.8.0_332, 11.0.15, 17.0.3 */
  private final String runtimeVersion;

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

  private final String hostName;
  private final String serviceName;
  private final boolean serviceNameSetByUser;
  private final Map<String, String> tags;
  private final Map<String, String> spanTags;
  private final String primaryTag;

  private final boolean debugEnabled;
  private final boolean reportHostName;
  private final boolean azureAppServices;
  private String env;
  private String version;
  private DogStatsDConfig dogStatsDConfig;
  private final boolean healthMetricsEnabled;
  private final String healthMetricsStatsdHost;
  private final Integer healthMetricsStatsdPort;
  private final boolean perfMetricsEnabled;
  private final boolean tracerMetricsEnabled;
  private final boolean tracerMetricsBufferingEnabled;
  private final int tracerMetricsMaxAggregates;
  private final int tracerMetricsMaxPending;
  private final boolean internalExitOnFailure;
  private final boolean dataStreamsEnabled;
  private final boolean telemetryEnabled;
  private final int telemetryHeartbeatInterval;

  public GeneralFeatureConfig(ConfigProvider configProvider) {
    this.startTimeMillis = System.currentTimeMillis();
    this.runtimeId = generateRuntimeId(configProvider);
    this.runtimeVersion = System.getProperty("java.version", "unknown");

    String tmpApiKey = readApiKey(configProvider);
    this.site = configProvider.getString(SITE, DEFAULT_SITE);

    this.hostName = initHostName();

    String userProvidedServiceName =
        configProvider.getStringExcludingSource(
            SERVICE, null, CapturedEnvironmentConfigSource.class, SERVICE_NAME);

    if (userProvidedServiceName == null) {
      this.serviceNameSetByUser = false;
      this.serviceName = configProvider.getString(SERVICE, DEFAULT_SERVICE_NAME, SERVICE_NAME);
    } else {
      this.serviceNameSetByUser = true;
      this.serviceName = userProvidedServiceName;
    }

    {
      final Map<String, String> tags = new HashMap<>(configProvider.getMergedMap(GLOBAL_TAGS));
      tags.putAll(configProvider.getMergedMap(TAGS));
      this.tags = getMapWithPropertiesDefinedByEnvironment(configProvider, tags, ENV, VERSION);
    }

    this.spanTags = configProvider.getMergedMap(SPAN_TAGS);
    this.primaryTag = configProvider.getString(PRIMARY_TAG);

    this.debugEnabled = isDebugMode();
    this.reportHostName =
        configProvider.getBoolean(TRACE_REPORT_HOSTNAME, DEFAULT_TRACE_REPORT_HOSTNAME);
    this.azureAppServices = configProvider.getBoolean(AZURE_APP_SERVICES, false);

    this.dogStatsDConfig = new DogStatsDConfig(configProvider);

    boolean runtimeMetricsEnabled = configProvider.getBoolean(RUNTIME_METRICS_ENABLED, true);

    // Writer.Builder createMonitor will use the values of the JMX fetch & agent to fill-in defaults
    this.healthMetricsEnabled =
        runtimeMetricsEnabled
            && configProvider.getBoolean(HEALTH_METRICS_ENABLED, DEFAULT_HEALTH_METRICS_ENABLED);
    this.healthMetricsStatsdHost = configProvider.getString(HEALTH_METRICS_STATSD_HOST);
    this.healthMetricsStatsdPort = configProvider.getInteger(HEALTH_METRICS_STATSD_PORT);
    this.perfMetricsEnabled =
        runtimeMetricsEnabled
            && isJavaVersionAtLeast(8)
            && configProvider.getBoolean(PERF_METRICS_ENABLED, DEFAULT_PERF_METRICS_ENABLED);

    this.tracerMetricsEnabled =
        isJavaVersionAtLeast(8) && configProvider.getBoolean(TRACER_METRICS_ENABLED, false);
    this.tracerMetricsBufferingEnabled =
        configProvider.getBoolean(TRACER_METRICS_BUFFERING_ENABLED, false);
    this.tracerMetricsMaxAggregates =
        configProvider.getInteger(TRACER_METRICS_MAX_AGGREGATES, 2048);
    this.tracerMetricsMaxPending = configProvider.getInteger(TRACER_METRICS_MAX_PENDING, 2048);

    telemetryEnabled = configProvider.getBoolean(TELEMETRY_ENABLED, DEFAULT_TELEMETRY_ENABLED);
    int telemetryInterval =
        configProvider.getInteger(
            TELEMETRY_HEARTBEAT_INTERVAL, DEFAULT_TELEMETRY_HEARTBEAT_INTERVAL);
    if (telemetryInterval < 1 || telemetryInterval > 3600) {
      LOGGER.warn(
          "Wrong Telemetry heartbeat interval: {}. The value must be in range 1-3600",
          telemetryInterval);
      telemetryInterval = DEFAULT_TELEMETRY_HEARTBEAT_INTERVAL;
    }
    telemetryHeartbeatInterval = telemetryInterval;

    internalExitOnFailure = configProvider.getBoolean(INTERNAL_EXIT_ON_FAILURE, false);

    dataStreamsEnabled =
        configProvider.getBoolean(DATA_STREAMS_ENABLED, DEFAULT_DATA_STREAMS_ENABLED);

    // Setting this last because we have a few places where this can come from
    this.apiKey = tmpApiKey;
  }

  private static String generateRuntimeId(ConfigProvider configProvider) {
    Config currentInstance = Config.get();
    if (currentInstance != null) {
      return currentInstance.getRuntimeId();
    } else if (configProvider.getBoolean(RUNTIME_ID_ENABLED, true)) {
      return UUID.randomUUID().toString();
    } else {
      return "";
    }
  }

  private static String readApiKey(ConfigProvider configProvider) {
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
        LOGGER.error("Cannot read API key from file {}, skipping", apiKeyFile, e);
      }
    }
    if (tmpApiKey == null) {
      final String oldProfilingApiKeyFile = configProvider.getString(PROFILING_API_KEY_FILE_OLD);
      tmpApiKey = getEnv(propertyNameToEnvironmentVariableName(PROFILING_API_KEY_OLD));
      if (oldProfilingApiKeyFile != null) {
        try {
          tmpApiKey =
              new String(
                      Files.readAllBytes(Paths.get(oldProfilingApiKeyFile)), StandardCharsets.UTF_8)
                  .trim();
        } catch (final IOException e) {
          LOGGER.error("Cannot read API key from file {}, skipping", oldProfilingApiKeyFile, e);
        }
      }
    }
    if (tmpApiKey == null) {
      final String veryOldProfilingApiKeyFile =
          configProvider.getString(PROFILING_API_KEY_FILE_VERY_OLD);
      tmpApiKey = getEnv(propertyNameToEnvironmentVariableName(PROFILING_API_KEY_VERY_OLD));
      if (veryOldProfilingApiKeyFile != null) {
        try {
          tmpApiKey =
              new String(
                      Files.readAllBytes(Paths.get(veryOldProfilingApiKeyFile)),
                      StandardCharsets.UTF_8)
                  .trim();
        } catch (final IOException e) {
          LOGGER.error("Cannot read API key from file {}, skipping", veryOldProfilingApiKeyFile, e);
        }
      }
    }
    return tmpApiKey;
  }

  /** Returns the detected hostname. First tries locally, then using DNS */
  private static String initHostName() {
    String possibleHostname;

    // Try environment variable.  This works in almost all environments
    if (isWindowsOS()) {
      possibleHostname = getEnv("COMPUTERNAME");
    } else {
      possibleHostname = getEnv("HOSTNAME");
    }

    if (possibleHostname != null && !possibleHostname.isEmpty()) {
      LOGGER.debug("Determined hostname from environment variable");
      return possibleHostname.trim();
    }

    // Try hostname command
    try (final BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(Runtime.getRuntime().exec("hostname").getInputStream()))) {
      possibleHostname = reader.readLine();
    } catch (final Throwable ignore) {
      // Ignore.  Hostname command is not always available
    }

    if (possibleHostname != null && !possibleHostname.isEmpty()) {
      LOGGER.debug("Determined hostname from hostname command");
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
    return getProp("os.name").startsWith("Windows");
  }

  private static boolean isDebugMode() {
    final String tracerDebugLevelSysprop = "dd.trace.debug";
    final String tracerDebugLevelProp = getProp(tracerDebugLevelSysprop);

    if (tracerDebugLevelProp != null) {
      return Boolean.parseBoolean(tracerDebugLevelProp);
    }

    final String tracerDebugLevelEnv = getEnv(toEnvVar(tracerDebugLevelSysprop));

    if (tracerDebugLevelEnv != null) {
      return Boolean.parseBoolean(tracerDebugLevelEnv);
    }
    return false;
  }

  private static String getEnv(String name) {
    String value = System.getenv(name);
    if (value != null) {
      ConfigCollector.get().put(name, value);
    }
    return value;
  }

  private static String getProp(String name) {
    return getProp(name, null);
  }

  private static String getProp(String name, String def) {
    String value = System.getProperty(name, def);
    if (value != null) {
      ConfigCollector.get().put(name, value);
    }
    return value;
  }

  @Nonnull
  public static Map<String, String> newHashMap(final int size) {
    return new HashMap<>(size + 1, 1f);
  }

  /**
   * @param map
   * @param propNames
   * @return new unmodifiable copy of {@param map} where properties are overwritten from environment
   */
  @Nonnull
  private Map<String, String> getMapWithPropertiesDefinedByEnvironment(
      @Nonnull ConfigProvider configProvider,
      @Nonnull final Map<String, String> map,
      @Nonnull final String... propNames) {
    final Map<String, String> res = new HashMap<>(map);
    for (final String propName : propNames) {
      final String val = configProvider.getString(propName);
      if (val != null) {
        res.put(propName, val);
      }
    }
    return Collections.unmodifiableMap(res);
  }

  public long getStartTimeMillis() {
    return this.startTimeMillis;
  }

  public String getRuntimeId() {
    return this.runtimeId;
  }

  public String getRuntimeVersion() {
    return this.runtimeVersion;
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
  public Map<String, String> getRuntimeTags() {
    return Collections.singletonMap(RUNTIME_ID_TAG, getRuntimeId());
  }

  public String getApiKey() {
    return this.apiKey;
  }

  public String getSite() {
    return this.site;
  }

  public String getHostName() {
    return this.hostName;
  }

  public String getServiceName() {
    return this.serviceName;
  }

  public boolean isServiceNameSetByUser() {
    return this.serviceNameSetByUser;
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

  private Map<String, String> getAzureAppServicesTags() {
    // These variable names and derivations are copied from the dotnet tracer
    // See
    // https://github.com/DataDog/dd-trace-dotnet/blob/master/tracer/src/Datadog.Trace/PlatformHelpers/AzureAppServices.cs
    // and
    // https://github.com/DataDog/dd-trace-dotnet/blob/master/tracer/src/Datadog.Trace/TraceContext.cs#L207
    Map<String, String> aasTags = new HashMap<>();

    /// The site name of the site instance in Azure where the traced application is running.
    String siteName = getEnv("WEBSITE_SITE_NAME");
    if (siteName != null) {
      aasTags.put("aas.site.name", siteName);
    }

    // The kind of application instance running in Azure.
    // Possible values: app, api, mobileapp, app_linux, app_linux_container, functionapp,
    // functionapp_linux, functionapp_linux_container

    // The type of application instance running in Azure.
    // Possible values: app, function
    if (getEnv("FUNCTIONS_WORKER_RUNTIME") != null
        || getEnv("FUNCTIONS_EXTENSIONS_VERSION") != null) {
      aasTags.put("aas.site.kind", "functionapp");
      aasTags.put("aas.site.type", "function");
    } else {
      aasTags.put("aas.site.kind", "app");
      aasTags.put("aas.site.type", "app");
    }

    //  The resource group of the site instance in Azure App Services
    String resourceGroup = getEnv("WEBSITE_RESOURCE_GROUP");
    if (resourceGroup != null) {
      aasTags.put("aas.resource.group", resourceGroup);
    }

    // Example: 8c500027-5f00-400e-8f00-60000000000f+apm-dotnet-EastUSwebspace
    // Format: {subscriptionId}+{planResourceGroup}-{hostedInRegion}
    String websiteOwner = getEnv("WEBSITE_OWNER_NAME");
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
      LOGGER.warn(
          "Unable to generate resource id subscription id: {}, site name: {}, resource group {}",
          subscriptionId,
          siteName,
          resourceGroup);
    }

    // The instance ID in Azure
    String instanceId = getEnv("WEBSITE_INSTANCE_ID");
    instanceId = instanceId == null ? "unknown" : instanceId;
    aasTags.put("aas.environment.instance_id", instanceId);

    // The instance name in Azure
    String instanceName = getEnv("COMPUTERNAME");
    instanceName = instanceName == null ? "unknown" : instanceName;
    aasTags.put("aas.environment.instance_name", instanceName);

    // The operating system in Azure
    String operatingSystem = getEnv("WEBSITE_OS");
    operatingSystem = operatingSystem == null ? "unknown" : operatingSystem;
    aasTags.put("aas.environment.os", operatingSystem);

    // The version of the extension installed
    String siteExtensionVersion = getEnv("DD_AAS_JAVA_EXTENSION_VERSION");
    siteExtensionVersion = siteExtensionVersion == null ? "unknown" : siteExtensionVersion;
    aasTags.put("aas.environment.extension_version", siteExtensionVersion);

    aasTags.put("aas.environment.runtime", getProp("java.vm.name", "unknown"));

    return aasTags;
  }

  public WellKnownTags getWellKnownTags() {
    return new WellKnownTags(
        getRuntimeId(),
        this.reportHostName ? getHostName() : "",
        getEnv(),
        this.serviceName,
        getVersion(),
        LANGUAGE_TAG_VALUE);
  }

  public String getPrimaryTag() {
    return this.primaryTag;
  }

  /**
   * Provide 'global' tags, i.e. tags set everywhere. We have to support old (dd.trace.global.tags)
   * version of this setting if new (dd.tags) version has not been specified.
   */
  public Map<String, String> getGlobalTags() {
    return this.tags;
  }

  public boolean isDebugEnabled() {
    return this.debugEnabled;
  }

  public boolean isReportHostName() {
    return this.reportHostName;
  }

  public String getEnv() {
    // intentionally not thread safe
    if (this.env == null) {
      this.env = getMergedSpanTags().get("env");
      if (this.env == null) {
        this.env = "";
      }
    }

    return this.env;
  }

  public String getVersion() {
    // intentionally not thread safe
    if (this.version == null) {
      this.version = getMergedSpanTags().get("version");
      if (this.version == null) {
        this.version = "";
      }
    }
    return this.version;
  }

  public boolean isAzureAppServices() {
    return this.azureAppServices;
  }

  public String getDogStatsDNamedPipe() {
    return this.dogStatsDConfig.namedPipe;
  }

  public int getDogStatsDStartDelay() {
    return this.dogStatsDConfig.startDelay;
  }

  public String getDogStatsDPath() {
    return this.dogStatsDConfig.path;
  }

  public List<String> getDogStatsDArgs() {
    return this.dogStatsDConfig.args;
  }

  public boolean isHealthMetricsEnabled() {
    return this.healthMetricsEnabled;
  }

  public String getHealthMetricsStatsdHost() {
    return this.healthMetricsStatsdHost;
  }

  public Integer getHealthMetricsStatsdPort() {
    return this.healthMetricsStatsdPort;
  }

  public boolean isPerfMetricsEnabled() {
    return this.perfMetricsEnabled;
  }

  public boolean isTracerMetricsEnabled() {
    return this.tracerMetricsEnabled;
  }

  public boolean isTracerMetricsBufferingEnabled() {
    return this.tracerMetricsBufferingEnabled;
  }

  public int getTracerMetricsMaxAggregates() {
    return this.tracerMetricsMaxAggregates;
  }

  public int getTracerMetricsMaxPending() {
    return this.tracerMetricsMaxPending;
  }

  public boolean isTelemetryEnabled() {
    return this.telemetryEnabled;
  }

  public int getTelemetryHeartbeatInterval() {
    return this.telemetryHeartbeatInterval;
  }

  public boolean isDataStreamsEnabled() {
    return this.dataStreamsEnabled;
  }

  public boolean isInternalExitOnFailure() {
    return this.internalExitOnFailure;
  }

  public Map<String, String> getMergedSpanTags() {
    // Do not include runtimeId into span tags: we only want that added to the root span
    final Map<String, String> result = newHashMap(getGlobalTags().size() + this.spanTags.size());
    result.putAll(getGlobalTags());
    result.putAll(this.spanTags);
    return Collections.unmodifiableMap(result);
  }

  private static class DogStatsDConfig {
    private final String namedPipe;
    private final int startDelay;
    private final String path;
    private final List<String> args;

    public DogStatsDConfig(ConfigProvider configProvider) {
      this.namedPipe = configProvider.getString(DOGSTATSD_NAMED_PIPE);
      this.startDelay =
          configProvider.getInteger(
              DOGSTATSD_START_DELAY, DEFAULT_DOGSTATSD_START_DELAY, JMX_FETCH_START_DELAY);
      this.path = configProvider.getString(DOGSTATSD_PATH);
      String dogStatsDArgsString = configProvider.getString(DOGSTATSD_ARGS);
      if (dogStatsDArgsString == null) {
        this.args = Collections.emptyList();
      } else {
        this.args =
            Collections.unmodifiableList(
                new ArrayList<>(parseStringIntoSetOfNonEmptyStrings(dogStatsDArgsString)));
      }
    }

    @Override
    public String toString() {
      return "DogStatsDConfig{"
          + "namedPipe='"
          + this.namedPipe
          + '\''
          + ", startDelay="
          + this.startDelay
          + ", path='"
          + this.path
          + '\''
          + ", args="
          + this.args
          + '}';
    }
  }

  @Override
  public String toString() {
    return "GeneralFeatureConfig{"
        + "startTimeMillis="
        + this.startTimeMillis
        + ", runtimeId='"
        + this.runtimeId
        + '\''
        + ", runtimeVersion='"
        + this.runtimeVersion
        + '\''
        + ", apiKey="
        + (this.apiKey == null ? "null" : "****")
        + ", site='"
        + this.site
        + '\''
        + ", hostName='"
        + this.hostName
        + '\''
        + ", serviceName='"
        + this.serviceName
        + '\''
        + ", serviceNameSetByUser="
        + this.serviceNameSetByUser
        + ", tags="
        + this.tags
        + ", spanTags="
        + this.spanTags
        + ", primaryTag='"
        + this.primaryTag
        + '\''
        + ", debugEnabled="
        + this.debugEnabled
        + ", reportHostName="
        + this.reportHostName
        + ", azureAppServices="
        + this.azureAppServices
        + ", env='"
        + this.env
        + '\''
        + ", version='"
        + this.version
        + '\''
        + ", dogStatsDConfig="
        + this.dogStatsDConfig
        + ", healthMetricsEnabled="
        + this.healthMetricsEnabled
        + ", healthMetricsStatsdHost='"
        + this.healthMetricsStatsdHost
        + '\''
        + ", healthMetricsStatsdPort="
        + this.healthMetricsStatsdPort
        + ", perfMetricsEnabled="
        + this.perfMetricsEnabled
        + ", tracerMetricsEnabled="
        + this.tracerMetricsEnabled
        + ", tracerMetricsBufferingEnabled="
        + this.tracerMetricsBufferingEnabled
        + ", tracerMetricsMaxAggregates="
        + this.tracerMetricsMaxAggregates
        + ", tracerMetricsMaxPending="
        + this.tracerMetricsMaxPending
        + ", internalExitOnFailure="
        + this.internalExitOnFailure
        + ", dataStreamsEnabled="
        + this.dataStreamsEnabled
        + ", telemetryEnabled="
        + this.telemetryEnabled
        + ", telemetryHeartbeatInterval="
        + this.telemetryHeartbeatInterval
        + '}';
  }
}
