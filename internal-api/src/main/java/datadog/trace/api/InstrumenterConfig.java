package datadog.trace.api;

import static datadog.trace.api.ConfigDefaults.DEFAULT_APPSEC_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CIVISIBILITY_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_INTEGRATIONS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_LOGS_INJECTION_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_RESOLVER_RESET_INTERVAL;
import static datadog.trace.api.ConfigDefaults.DEFAULT_RUNTIME_CONTEXT_FIELD_INJECTION;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SERIALVERSIONUID_FIELD_INJECTION;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TELEMETRY_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_ANNOTATIONS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_EXECUTORS_ALL;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_METHODS;
import static datadog.trace.api.config.AppSecConfig.APPSEC_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_ENABLED;
import static datadog.trace.api.config.GeneralConfig.INTERNAL_EXIT_ON_FAILURE;
import static datadog.trace.api.config.GeneralConfig.TELEMETRY_ENABLED;
import static datadog.trace.api.config.IastConfig.IAST_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DIRECT_ALLOCATION_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DIRECT_ALLOCATION_ENABLED_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED_DEFAULT;
import static datadog.trace.api.config.TraceInstrumentationConfig.INTEGRATIONS_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.JDBC_CONNECTION_CLASS_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.JDBC_PREPARED_STATEMENT_CLASS_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.LEGACY_INSTALLER_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.LOGS_INJECTION_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.LOGS_MDC_TAGS_INJECTION_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.RESOLVER_CACHE_CONFIG;
import static datadog.trace.api.config.TraceInstrumentationConfig.RESOLVER_RESET_INTERVAL;
import static datadog.trace.api.config.TraceInstrumentationConfig.RESOLVER_USE_LOADCLASS;
import static datadog.trace.api.config.TraceInstrumentationConfig.RUNTIME_CONTEXT_FIELD_INJECTION;
import static datadog.trace.api.config.TraceInstrumentationConfig.SERIALVERSIONUID_FIELD_INJECTION;
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
import static datadog.trace.util.CollectionUtils.tryMakeImmutableList;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableSet;

import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.config.provider.SystemPropertiesConfigSource;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class InstrumenterConfig {
  private final ConfigProvider configProvider;

  private final boolean integrationsEnabled;

  private final boolean traceEnabled;
  private final boolean logsInjectionEnabled;
  private final boolean logsMDCTagsInjectionEnabled;
  private final boolean profilingEnabled;
  private final boolean ciVisibilityEnabled;
  private final ProductActivation appSecActivation;
  private final boolean iastEnabled;
  private final boolean telemetryEnabled;

  private final boolean traceExecutorsAll;
  private final List<String> traceExecutors;
  private final Set<String> traceThreadPoolExecutorsExclude;

  private final String jdbcPreparedStatementClassName;
  private final String jdbcConnectionClassName;

  private final boolean directAllocationProfilingEnabled;

  private final List<String> excludedClasses;
  private final String excludedClassesFile;
  private final Set<String> excludedClassLoaders;
  private final List<String> excludedCodeSources;

  private final ResolverCacheConfig resolverCacheConfig;
  private final boolean resolverUseLoadClassEnabled;
  private final int resolverResetInterval;

  private final boolean runtimeContextFieldInjection;
  private final boolean serialVersionUIDFieldInjection;

  private final String traceAnnotations;
  private final String traceMethods;

  private final boolean internalExitOnFailure;

  private final boolean legacyInstallerEnabled;

  private InstrumenterConfig() {
    this(ConfigProvider.createDefault());
  }

  InstrumenterConfig(ConfigProvider configProvider) {
    this.configProvider = configProvider;

    integrationsEnabled =
        configProvider.getBoolean(INTEGRATIONS_ENABLED, DEFAULT_INTEGRATIONS_ENABLED);

    traceEnabled = configProvider.getBoolean(TRACE_ENABLED, DEFAULT_TRACE_ENABLED);
    logsInjectionEnabled =
        configProvider.getBoolean(LOGS_INJECTION_ENABLED, DEFAULT_LOGS_INJECTION_ENABLED);
    logsMDCTagsInjectionEnabled = configProvider.getBoolean(LOGS_MDC_TAGS_INJECTION_ENABLED, true);

    if (!Platform.isNativeImageBuilder()) {
      profilingEnabled = configProvider.getBoolean(PROFILING_ENABLED, PROFILING_ENABLED_DEFAULT);
      ciVisibilityEnabled =
          configProvider.getBoolean(CIVISIBILITY_ENABLED, DEFAULT_CIVISIBILITY_ENABLED);
      // ConfigProvider.getString currently doesn't fallback to default for empty strings. We have
      // special handling here until we have a general solution for empty string value fallback.
      String appSecEnabled = configProvider.getString(APPSEC_ENABLED);
      if (appSecEnabled == null || appSecEnabled.isEmpty()) {
        appSecEnabled =
            configProvider.getStringExcludingSource(
                APPSEC_ENABLED, DEFAULT_APPSEC_ENABLED, SystemPropertiesConfigSource.class);
        if (appSecEnabled.isEmpty()) {
          appSecEnabled = DEFAULT_APPSEC_ENABLED;
        }
      }
      appSecActivation = ProductActivation.fromString(appSecEnabled);
      iastEnabled = configProvider.getBoolean(IAST_ENABLED, DEFAULT_IAST_ENABLED);
      telemetryEnabled = configProvider.getBoolean(TELEMETRY_ENABLED, DEFAULT_TELEMETRY_ENABLED);
    } else {
      // disable these features in native-image
      profilingEnabled = false;
      ciVisibilityEnabled = false;
      appSecActivation = ProductActivation.FULLY_DISABLED;
      iastEnabled = false;
      telemetryEnabled = false;
    }

    traceExecutorsAll = configProvider.getBoolean(TRACE_EXECUTORS_ALL, DEFAULT_TRACE_EXECUTORS_ALL);
    traceExecutors = tryMakeImmutableList(configProvider.getList(TRACE_EXECUTORS));
    traceThreadPoolExecutorsExclude =
        tryMakeImmutableSet(configProvider.getList(TRACE_THREAD_POOL_EXECUTORS_EXCLUDE));

    jdbcPreparedStatementClassName =
        configProvider.getString(JDBC_PREPARED_STATEMENT_CLASS_NAME, "");
    jdbcConnectionClassName = configProvider.getString(JDBC_CONNECTION_CLASS_NAME, "");

    directAllocationProfilingEnabled =
        configProvider.getBoolean(
            PROFILING_DIRECT_ALLOCATION_ENABLED, PROFILING_DIRECT_ALLOCATION_ENABLED_DEFAULT);

    excludedClasses = tryMakeImmutableList(configProvider.getList(TRACE_CLASSES_EXCLUDE));
    excludedClassesFile = configProvider.getString(TRACE_CLASSES_EXCLUDE_FILE);
    excludedClassLoaders = tryMakeImmutableSet(configProvider.getList(TRACE_CLASSLOADERS_EXCLUDE));
    excludedCodeSources = tryMakeImmutableList(configProvider.getList(TRACE_CODESOURCES_EXCLUDE));

    resolverCacheConfig =
        configProvider.getEnum(
            RESOLVER_CACHE_CONFIG, ResolverCacheConfig.class, ResolverCacheConfig.DEFAULT);
    resolverUseLoadClassEnabled = configProvider.getBoolean(RESOLVER_USE_LOADCLASS, true);
    resolverResetInterval =
        Platform.isNativeImageBuilder()
            ? 0
            : configProvider.getInteger(RESOLVER_RESET_INTERVAL, DEFAULT_RESOLVER_RESET_INTERVAL);

    runtimeContextFieldInjection =
        configProvider.getBoolean(
            RUNTIME_CONTEXT_FIELD_INJECTION, DEFAULT_RUNTIME_CONTEXT_FIELD_INJECTION);
    serialVersionUIDFieldInjection =
        configProvider.getBoolean(
            SERIALVERSIONUID_FIELD_INJECTION, DEFAULT_SERIALVERSIONUID_FIELD_INJECTION);

    traceAnnotations = configProvider.getString(TRACE_ANNOTATIONS, DEFAULT_TRACE_ANNOTATIONS);
    traceMethods = configProvider.getString(TRACE_METHODS, DEFAULT_TRACE_METHODS);

    internalExitOnFailure = configProvider.getBoolean(INTERNAL_EXIT_ON_FAILURE, false);

    legacyInstallerEnabled = configProvider.getBoolean(LEGACY_INSTALLER_ENABLED, false);
  }

  public boolean isIntegrationsEnabled() {
    return integrationsEnabled;
  }

  public boolean isIntegrationEnabled(
      final Iterable<String> integrationNames, final boolean defaultEnabled) {
    return configProvider.isEnabled(integrationNames, "integration.", ".enabled", defaultEnabled);
  }

  public boolean isIntegrationShortcutMatchingEnabled(
      final Iterable<String> integrationNames, final boolean defaultEnabled) {
    return configProvider.isEnabled(
        integrationNames, "integration.", ".matching.shortcut.enabled", defaultEnabled);
  }

  public boolean isTraceEnabled() {
    return traceEnabled;
  }

  public boolean isLogsInjectionEnabled() {
    return logsInjectionEnabled;
  }

  public boolean isLogsMDCTagsInjectionEnabled() {
    return logsMDCTagsInjectionEnabled && !Platform.isNativeImageBuilder();
  }

  public boolean isProfilingEnabled() {
    return profilingEnabled;
  }

  public boolean isCiVisibilityEnabled() {
    return ciVisibilityEnabled;
  }

  public ProductActivation getAppSecActivation() {
    return appSecActivation;
  }

  public boolean isIastEnabled() {
    return iastEnabled;
  }

  public boolean isTelemetryEnabled() {
    return telemetryEnabled;
  }

  public boolean isTraceExecutorsAll() {
    return traceExecutorsAll;
  }

  public List<String> getTraceExecutors() {
    return traceExecutors;
  }

  public Set<String> getTraceThreadPoolExecutorsExclude() {
    return traceThreadPoolExecutorsExclude;
  }

  public String getJdbcPreparedStatementClassName() {
    return jdbcPreparedStatementClassName;
  }

  public String getJdbcConnectionClassName() {
    return jdbcConnectionClassName;
  }

  public boolean isDirectAllocationProfilingEnabled() {
    return directAllocationProfilingEnabled;
  }

  public List<String> getExcludedClasses() {
    return excludedClasses;
  }

  public String getExcludedClassesFile() {
    return excludedClassesFile;
  }

  public Set<String> getExcludedClassLoaders() {
    return excludedClassLoaders;
  }

  public List<String> getExcludedCodeSources() {
    return excludedCodeSources;
  }

  public boolean isResolverOutliningEnabled() {
    return resolverCacheConfig.outlinePoolSize() > 0;
  }

  public int getResolverOutlinePoolSize() {
    return resolverCacheConfig.outlinePoolSize();
  }

  public int getResolverTypePoolSize() {
    return resolverCacheConfig.typePoolSize();
  }

  public boolean isResolverUseLoadClassEnabled() {
    return resolverUseLoadClassEnabled;
  }

  public int getResolverResetInterval() {
    return resolverResetInterval;
  }

  public boolean isRuntimeContextFieldInjection() {
    return runtimeContextFieldInjection;
  }

  public boolean isSerialVersionUIDFieldInjection() {
    return serialVersionUIDFieldInjection;
  }

  public String getTraceAnnotations() {
    return traceAnnotations;
  }

  public String getTraceMethods() {
    return traceMethods;
  }

  public boolean isInternalExitOnFailure() {
    return internalExitOnFailure;
  }

  public boolean isLegacyInstallerEnabled() {
    return legacyInstallerEnabled;
  }

  public boolean isLegacyInstrumentationEnabled(
      final boolean defaultEnabled, final String... integrationNames) {
    return configProvider.isEnabled(
        Arrays.asList(integrationNames), "", ".legacy.tracing.enabled", defaultEnabled);
  }

  // This has to be placed after all other static fields to give them a chance to initialize
  @SuppressFBWarnings("SI_INSTANCE_BEFORE_FINALS_ASSIGNED")
  private static final InstrumenterConfig INSTANCE =
      new InstrumenterConfig(
          Platform.isNativeImageBuilder()
              ? ConfigProvider.withoutCollector()
              : ConfigProvider.getInstance());

  public static InstrumenterConfig get() {
    return INSTANCE;
  }

  @Override
  public String toString() {
    return "InstrumenterConfig{"
        + "integrationsEnabled="
        + integrationsEnabled
        + ", traceEnabled="
        + traceEnabled
        + ", logsInjectionEnabled="
        + logsInjectionEnabled
        + ", logsMDCTagsInjectionEnabled="
        + logsMDCTagsInjectionEnabled
        + ", profilingEnabled="
        + profilingEnabled
        + ", ciVisibilityEnabled="
        + ciVisibilityEnabled
        + ", appSecActivation="
        + appSecActivation
        + ", iastEnabled="
        + iastEnabled
        + ", telemetryEnabled="
        + telemetryEnabled
        + ", traceExecutorsAll="
        + traceExecutorsAll
        + ", traceExecutors="
        + traceExecutors
        + ", jdbcPreparedStatementClassName='"
        + jdbcPreparedStatementClassName
        + '\''
        + ", jdbcConnectionClassName='"
        + jdbcConnectionClassName
        + '\''
        + ", excludedClasses="
        + excludedClasses
        + ", excludedClassesFile="
        + excludedClassesFile
        + ", excludedClassLoaders="
        + excludedClassLoaders
        + ", excludedCodeSources="
        + excludedCodeSources
        + ", resolverCacheConfig="
        + resolverCacheConfig
        + ", resolverUseLoadClassEnabled="
        + resolverUseLoadClassEnabled
        + ", resolverResetInterval="
        + resolverResetInterval
        + ", runtimeContextFieldInjection="
        + runtimeContextFieldInjection
        + ", serialVersionUIDFieldInjection="
        + serialVersionUIDFieldInjection
        + ", traceAnnotations='"
        + traceAnnotations
        + '\''
        + ", traceMethods='"
        + traceMethods
        + '\''
        + ", internalExitOnFailure="
        + internalExitOnFailure
        + ", legacyInstallerEnabled="
        + legacyInstallerEnabled
        + '}';
  }
}
