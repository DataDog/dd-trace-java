package datadog.trace.api;

import static datadog.trace.api.ConfigDefaults.DEFAULT_APPSEC_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CIVISIBILITY_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CODE_ORIGIN_FOR_SPANS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_INTEGRATIONS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_LLM_OBS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_MEASURE_METHODS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_RESOLVER_RESET_INTERVAL;
import static datadog.trace.api.ConfigDefaults.DEFAULT_RUM_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_RUNTIME_CONTEXT_FIELD_INJECTION;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SERIALVERSIONUID_FIELD_INJECTION;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TELEMETRY_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_ANNOTATIONS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_ANNOTATION_ASYNC;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_EXECUTORS_ALL;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_METHODS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_OTEL_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_USM_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_WEBSOCKET_MESSAGES_ENABLED;
import static datadog.trace.api.config.AppSecConfig.APPSEC_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_ENABLED;
import static datadog.trace.api.config.GeneralConfig.INTERNAL_EXIT_ON_FAILURE;
import static datadog.trace.api.config.GeneralConfig.TELEMETRY_ENABLED;
import static datadog.trace.api.config.GeneralConfig.TRACE_DEBUG;
import static datadog.trace.api.config.GeneralConfig.TRACE_TRIAGE;
import static datadog.trace.api.config.GeneralConfig.TRIAGE_REPORT_TRIGGER;
import static datadog.trace.api.config.IastConfig.IAST_ENABLED;
import static datadog.trace.api.config.LlmObsConfig.LLMOBS_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DIRECT_ALLOCATION_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DIRECT_ALLOCATION_ENABLED_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED_DEFAULT;
import static datadog.trace.api.config.RumConfig.RUM_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.AKKA_FORK_JOIN_EXECUTOR_TASK_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.AKKA_FORK_JOIN_POOL_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.AKKA_FORK_JOIN_TASK_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.AXIS_TRANSPORT_CLASS_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.CODE_ORIGIN_FOR_SPANS_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.EXPERIMENTAL_DEFER_INTEGRATIONS_UNTIL;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_URL_CONNECTION_CLASS_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.INSTRUMENTATION_CONFIG_ID;
import static datadog.trace.api.config.TraceInstrumentationConfig.INTEGRATIONS_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.JAX_RS_ADDITIONAL_ANNOTATIONS;
import static datadog.trace.api.config.TraceInstrumentationConfig.JDBC_CONNECTION_CLASS_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.JDBC_POOL_WAITING_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.JDBC_PREPARED_STATEMENT_CLASS_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.MEASURE_METHODS;
import static datadog.trace.api.config.TraceInstrumentationConfig.RESOLVER_CACHE_CONFIG;
import static datadog.trace.api.config.TraceInstrumentationConfig.RESOLVER_CACHE_DIR;
import static datadog.trace.api.config.TraceInstrumentationConfig.RESOLVER_NAMES_ARE_UNIQUE;
import static datadog.trace.api.config.TraceInstrumentationConfig.RESOLVER_RESET_INTERVAL;
import static datadog.trace.api.config.TraceInstrumentationConfig.RESOLVER_SIMPLE_METHOD_GRAPH;
import static datadog.trace.api.config.TraceInstrumentationConfig.RESOLVER_USE_LOADCLASS;
import static datadog.trace.api.config.TraceInstrumentationConfig.RESOLVER_USE_URL_CACHES;
import static datadog.trace.api.config.TraceInstrumentationConfig.RUNTIME_CONTEXT_FIELD_INJECTION;
import static datadog.trace.api.config.TraceInstrumentationConfig.SERIALVERSIONUID_FIELD_INJECTION;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_ANNOTATIONS;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_ANNOTATION_ASYNC;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_CLASSES_EXCLUDE;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_CLASSES_EXCLUDE_FILE;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_CLASSLOADERS_DEFER;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_CLASSLOADERS_EXCLUDE;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_CODESOURCES_EXCLUDE;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_EXECUTORS;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_EXECUTORS_ALL;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_EXTENSIONS_PATH;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_METHODS;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_OTEL_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_PEKKO_SCHEDULER_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_THREAD_POOL_EXECUTORS_EXCLUDE;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_WEBSOCKET_MESSAGES_ENABLED;
import static datadog.trace.api.config.UsmConfig.USM_ENABLED;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableList;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableSet;

import datadog.trace.api.profiling.ProfilingEnablement;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This config is needed before instrumentation is applied
 *
 * <p>For example anything that changes what advice is applied, or what classes are instrumented
 *
 * <p>This config will be baked into native-images at build time, because instrumentation is also
 * baked in at that point
 *
 * <p>Config that is accessed from inside advice, for example during application runtime after the
 * advice has been applied, shouldn't be in {@link InstrumenterConfig} (it really should just be
 * config that must be there ahead of instrumentation)
 *
 * @see DynamicConfig for configuration that can be dynamically updated via remote-config
 * @see Config for other configurations
 */
public class InstrumenterConfig {
  private final ConfigProvider configProvider;

  private final boolean triageEnabled;

  private final boolean integrationsEnabled;

  private final boolean codeOriginEnabled;
  private final boolean traceEnabled;
  private final boolean traceOtelEnabled;
  private final ProfilingEnablement profilingEnabled;
  private final boolean ciVisibilityEnabled;
  private final ProductActivation appSecActivation;
  private final ProductActivation iastActivation;
  private final boolean iastFullyDisabled;
  private final boolean usmEnabled;
  private final boolean telemetryEnabled;
  private final boolean llmObsEnabled;

  private final String traceExtensionsPath;

  private final boolean traceExecutorsAll;
  private final List<String> traceExecutors;
  private final Set<String> traceThreadPoolExecutorsExclude;

  private final String jdbcPreparedStatementClassName;
  private final String jdbcConnectionClassName;
  private final boolean jdbcPoolWaitingEnabled;

  private final String httpURLConnectionClassName;
  private final String axisTransportClassName;
  private final boolean websocketTracingEnabled;
  private final boolean pekkoSchedulerEnabled;

  private final String akkaForkJoinTaskName;
  private final String akkaForkJoinExecutorTaskName;
  private final String akkaForkJoinPoolName;

  private final boolean directAllocationProfilingEnabled;

  private final String instrumentationConfigId;

  private final List<String> excludedClasses;
  private final String excludedClassesFile;
  private final Set<String> excludedClassLoaders;
  private final List<String> excludedCodeSources;
  private final Set<String> deferredClassLoaders;

  private final String deferIntegrationsUntil;

  private final ResolverCacheConfig resolverCacheConfig;
  private final String resolverCacheDir;
  private final boolean resolverNamesAreUnique;
  private final boolean resolverSimpleMethodGraph;
  private final boolean resolverUseLoadClass;
  private final Boolean resolverUseUrlCaches;
  private final int resolverResetInterval;

  private final boolean runtimeContextFieldInjection;
  private final boolean serialVersionUIDFieldInjection;

  private final String traceAnnotations;
  private final boolean traceAnnotationAsync;
  private final Map<String, Set<String>> traceMethods;
  private final Map<String, Set<String>> measureMethods;

  private final boolean internalExitOnFailure;

  private final Collection<String> additionalJaxRsAnnotations;

  private final boolean rumEnabled;

  private InstrumenterConfig() {
    this(ConfigProvider.createDefault());
  }

  InstrumenterConfig(ConfigProvider configProvider) {
    this.configProvider = configProvider;

    if (null != configProvider.getString(TRIAGE_REPORT_TRIGGER)) {
      triageEnabled = true; // explicitly setting a trigger implies triage mode
    } else {
      // default to same state as debug mode, unless explicitly overridden
      boolean debugEnabled = configProvider.getBoolean(TRACE_DEBUG, false);
      triageEnabled = configProvider.getBoolean(TRACE_TRIAGE, debugEnabled);
    }

    integrationsEnabled =
        configProvider.getBoolean(INTEGRATIONS_ENABLED, DEFAULT_INTEGRATIONS_ENABLED);

    codeOriginEnabled =
        configProvider.getBoolean(
            CODE_ORIGIN_FOR_SPANS_ENABLED, DEFAULT_CODE_ORIGIN_FOR_SPANS_ENABLED);
    traceEnabled = configProvider.getBoolean(TRACE_ENABLED, DEFAULT_TRACE_ENABLED);
    traceOtelEnabled = configProvider.getBoolean(TRACE_OTEL_ENABLED, DEFAULT_TRACE_OTEL_ENABLED);

    profilingEnabled =
        ProfilingEnablement.of(
            configProvider.getString(PROFILING_ENABLED, String.valueOf(PROFILING_ENABLED_DEFAULT)));
    rumEnabled = configProvider.getBoolean(RUM_ENABLED, DEFAULT_RUM_ENABLED);
    if (!Platform.isNativeImageBuilder()) {
      ciVisibilityEnabled =
          configProvider.getBoolean(CIVISIBILITY_ENABLED, DEFAULT_CIVISIBILITY_ENABLED);
      appSecActivation =
          ProductActivation.fromString(
              configProvider.getStringNotEmpty(APPSEC_ENABLED, DEFAULT_APPSEC_ENABLED));
      iastActivation =
          ProductActivation.fromString(
              configProvider.getStringNotEmpty(IAST_ENABLED, DEFAULT_IAST_ENABLED));
      final Boolean iastEnabled = configProvider.getBoolean(IAST_ENABLED);
      iastFullyDisabled = iastEnabled != null && !iastEnabled;
      usmEnabled = configProvider.getBoolean(USM_ENABLED, DEFAULT_USM_ENABLED);
      telemetryEnabled = configProvider.getBoolean(TELEMETRY_ENABLED, DEFAULT_TELEMETRY_ENABLED);
      llmObsEnabled = configProvider.getBoolean(LLMOBS_ENABLED, DEFAULT_LLM_OBS_ENABLED);
    } else {
      // disable these features in native-image
      ciVisibilityEnabled = false;
      appSecActivation = ProductActivation.FULLY_DISABLED;
      iastActivation = ProductActivation.FULLY_DISABLED;
      iastFullyDisabled = true;
      telemetryEnabled = false;
      usmEnabled = false;
      llmObsEnabled = false;
    }

    traceExtensionsPath = configProvider.getString(TRACE_EXTENSIONS_PATH);

    traceExecutorsAll = configProvider.getBoolean(TRACE_EXECUTORS_ALL, DEFAULT_TRACE_EXECUTORS_ALL);
    traceExecutors = tryMakeImmutableList(configProvider.getList(TRACE_EXECUTORS));
    traceThreadPoolExecutorsExclude =
        tryMakeImmutableSet(configProvider.getList(TRACE_THREAD_POOL_EXECUTORS_EXCLUDE));

    jdbcPreparedStatementClassName =
        configProvider.getString(JDBC_PREPARED_STATEMENT_CLASS_NAME, "");
    jdbcConnectionClassName = configProvider.getString(JDBC_CONNECTION_CLASS_NAME, "");
    jdbcPoolWaitingEnabled = configProvider.getBoolean(JDBC_POOL_WAITING_ENABLED, false);

    httpURLConnectionClassName = configProvider.getString(HTTP_URL_CONNECTION_CLASS_NAME, "");
    axisTransportClassName = configProvider.getString(AXIS_TRANSPORT_CLASS_NAME, "");

    akkaForkJoinTaskName = configProvider.getString(AKKA_FORK_JOIN_TASK_NAME, "");
    akkaForkJoinExecutorTaskName = configProvider.getString(AKKA_FORK_JOIN_EXECUTOR_TASK_NAME, "");
    akkaForkJoinPoolName = configProvider.getString(AKKA_FORK_JOIN_POOL_NAME, "");

    directAllocationProfilingEnabled =
        configProvider.getBoolean(
            PROFILING_DIRECT_ALLOCATION_ENABLED, PROFILING_DIRECT_ALLOCATION_ENABLED_DEFAULT);

    excludedClasses = tryMakeImmutableList(configProvider.getList(TRACE_CLASSES_EXCLUDE));
    excludedClassesFile = configProvider.getString(TRACE_CLASSES_EXCLUDE_FILE);
    excludedClassLoaders = tryMakeImmutableSet(configProvider.getList(TRACE_CLASSLOADERS_EXCLUDE));
    excludedCodeSources = tryMakeImmutableList(configProvider.getList(TRACE_CODESOURCES_EXCLUDE));
    deferredClassLoaders = tryMakeImmutableSet(configProvider.getList(TRACE_CLASSLOADERS_DEFER));

    deferIntegrationsUntil = configProvider.getString(EXPERIMENTAL_DEFER_INTEGRATIONS_UNTIL);

    resolverCacheConfig =
        configProvider.getEnum(
            RESOLVER_CACHE_CONFIG, ResolverCacheConfig.class, ResolverCacheConfig.MEMOS);
    resolverCacheDir = configProvider.getString(RESOLVER_CACHE_DIR);
    resolverNamesAreUnique = configProvider.getBoolean(RESOLVER_NAMES_ARE_UNIQUE, false);
    resolverSimpleMethodGraph =
        // use simpler approach everywhere except GraalVM, where it affects reachability analysis
        configProvider.getBoolean(RESOLVER_SIMPLE_METHOD_GRAPH, !Platform.isNativeImageBuilder());
    resolverUseLoadClass = configProvider.getBoolean(RESOLVER_USE_LOADCLASS, true);
    resolverUseUrlCaches = configProvider.getBoolean(RESOLVER_USE_URL_CACHES);
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

    instrumentationConfigId = configProvider.getString(INSTRUMENTATION_CONFIG_ID, "");

    traceAnnotations = configProvider.getString(TRACE_ANNOTATIONS, DEFAULT_TRACE_ANNOTATIONS);
    traceAnnotationAsync =
        configProvider.getBoolean(TRACE_ANNOTATION_ASYNC, DEFAULT_TRACE_ANNOTATION_ASYNC);
    traceMethods =
        MethodFilterConfigParser.parse(
            configProvider.getString(TRACE_METHODS, DEFAULT_TRACE_METHODS));
    measureMethods =
        MethodFilterConfigParser.parse(
            configProvider.getString(MEASURE_METHODS, DEFAULT_MEASURE_METHODS));
    internalExitOnFailure = configProvider.getBoolean(INTERNAL_EXIT_ON_FAILURE, false);

    this.additionalJaxRsAnnotations =
        tryMakeImmutableSet(configProvider.getList(JAX_RS_ADDITIONAL_ANNOTATIONS));
    this.websocketTracingEnabled =
        configProvider.getBoolean(
            TRACE_WEBSOCKET_MESSAGES_ENABLED, DEFAULT_WEBSOCKET_MESSAGES_ENABLED);
    this.pekkoSchedulerEnabled = configProvider.getBoolean(TRACE_PEKKO_SCHEDULER_ENABLED, false);
  }

  public boolean isCodeOriginEnabled() {
    return codeOriginEnabled;
  }

  public boolean isTriageEnabled() {
    return triageEnabled;
  }

  public boolean isIntegrationsEnabled() {
    return integrationsEnabled;
  }

  /**
   * isIntegrationEnabled determines whether an integration under the specified name(s) is enabled
   * according to the following list of configurations, from highest to lowest precedence:
   * trace.name.enabled, trace.integration.name.enabled, integration.name.enabled. If none of these
   * configurations is set, the defaultEnabled value is used. All system properties take precedence
   * over all env vars.
   *
   * @param integrationNames the name(s) that represent(s) the integration
   * @param defaultEnabled true if enabled by default, else false
   * @return boolean on whether the integration is enabled
   */
  public boolean isIntegrationEnabled(
      final Iterable<String> integrationNames, final boolean defaultEnabled) {
    // If default is enabled, we want to disable individually.
    // If default is disabled, we want to enable individually.
    boolean anyEnabled = defaultEnabled;
    for (final String name : integrationNames) {
      final String primaryKey = "trace." + name + ".enabled";
      final String[] aliases = {
        "trace.integration." + name + ".enabled", "integration." + name + ".enabled"
      }; // listed in order of precedence
      final boolean configEnabled = configProvider.getBoolean(primaryKey, defaultEnabled, aliases);
      if (defaultEnabled) {
        anyEnabled &= configEnabled;
      } else {
        anyEnabled |= configEnabled;
      }
    }
    return anyEnabled;
  }

  public boolean isIntegrationShortcutMatchingEnabled(
      final Iterable<String> integrationNames, final boolean defaultEnabled) {
    return configProvider.isEnabled(
        integrationNames, "integration.", ".matching.shortcut.enabled", defaultEnabled);
  }

  public boolean isTraceEnabled() {
    return traceEnabled;
  }

  public boolean isTraceOtelEnabled() {
    return traceOtelEnabled;
  }

  public boolean isProfilingEnabled() {
    return profilingEnabled.isActive();
  }

  public boolean isCiVisibilityEnabled() {
    return ciVisibilityEnabled;
  }

  public ProductActivation getAppSecActivation() {
    return appSecActivation;
  }

  public ProductActivation getIastActivation() {
    return iastActivation;
  }

  public boolean isIastFullyDisabled() {
    return iastFullyDisabled;
  }

  public boolean isLlmObsEnabled() {
    return llmObsEnabled;
  }

  public boolean isUsmEnabled() {
    return usmEnabled;
  }

  public boolean isTelemetryEnabled() {
    return telemetryEnabled;
  }

  public String getTraceExtensionsPath() {
    return traceExtensionsPath;
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

  public boolean isJdbcPoolWaitingEnabled() {
    return jdbcPoolWaitingEnabled;
  }

  public String getHttpURLConnectionClassName() {
    return httpURLConnectionClassName;
  }

  public String getAxisTransportClassName() {
    return axisTransportClassName;
  }

  public String getAkkaForkJoinTaskName() {
    return akkaForkJoinTaskName;
  }

  public String getAkkaForkJoinExecutorTaskName() {
    return akkaForkJoinExecutorTaskName;
  }

  public String getAkkaForkJoinPoolName() {
    return akkaForkJoinPoolName;
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

  public Set<String> getDeferredClassLoaders() {
    return deferredClassLoaders;
  }

  public String deferIntegrationsUntil() {
    return deferIntegrationsUntil;
  }

  public int getResolverNoMatchesSize() {
    return resolverCacheConfig.noMatchesSize();
  }

  public int getResolverVisibilitySize() {
    return resolverCacheConfig.visibilitySize();
  }

  public boolean isResolverMemoizingEnabled() {
    return resolverCacheConfig.memoPoolSize() > 0;
  }

  public int getResolverMemoPoolSize() {
    return resolverCacheConfig.memoPoolSize();
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

  public String getResolverCacheDir() {
    return resolverCacheDir;
  }

  public String getInstrumentationConfigId() {
    return instrumentationConfigId;
  }

  public boolean isResolverNamesAreUnique() {
    return resolverNamesAreUnique;
  }

  public boolean isResolverSimpleMethodGraph() {
    return resolverSimpleMethodGraph;
  }

  public boolean isResolverUseLoadClass() {
    return resolverUseLoadClass;
  }

  public Boolean isResolverUseUrlCaches() {
    return resolverUseUrlCaches;
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

  public Collection<String> getAdditionalJaxRsAnnotations() {
    return additionalJaxRsAnnotations;
  }

  public boolean isWebsocketTracingEnabled() {
    return websocketTracingEnabled;
  }

  public boolean isPekkoSchedulerEnabled() {
    return pekkoSchedulerEnabled;
  }

  /**
   * Check whether asynchronous result types are supported with @Trace annotation.
   *
   * @return {@code true} if supported, {@code false} otherwise.
   */
  public boolean isTraceAnnotationAsync() {
    return traceAnnotationAsync;
  }

  public Map<String, Set<String>> getTraceMethods() {
    return traceMethods;
  }

  public boolean isMethodMeasured(Method method) {
    if (this.measureMethods.isEmpty()) {
      return false;
    }
    String clazz = method.getDeclaringClass().getName();
    Set<String> methods = this.measureMethods.get(clazz);
    return methods != null && (methods.contains(method.getName()) || methods.contains("*"));
  }

  public boolean isInternalExitOnFailure() {
    return internalExitOnFailure;
  }

  public boolean isLegacyInstrumentationEnabled(
      final boolean defaultEnabled, final String... integrationNames) {
    return configProvider.isEnabled(
        Arrays.asList(integrationNames), "", ".legacy.tracing.enabled", defaultEnabled);
  }

  public boolean isRumEnabled() {
    return rumEnabled;
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
        + ", traceOtelEnabled="
        + traceOtelEnabled
        + ", profilingEnabled="
        + profilingEnabled
        + ", ciVisibilityEnabled="
        + ciVisibilityEnabled
        + ", appSecActivation="
        + appSecActivation
        + ", iastActivation="
        + iastActivation
        + ", usmEnabled="
        + usmEnabled
        + ", telemetryEnabled="
        + telemetryEnabled
        + ", traceExtensionsPath="
        + traceExtensionsPath
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
        + ", jdbcPoolWaitingEnabled="
        + jdbcPoolWaitingEnabled
        + ", httpURLConnectionClassName='"
        + httpURLConnectionClassName
        + '\''
        + ", axisTransportClassName='"
        + axisTransportClassName
        + '\''
        + ", excludedClasses="
        + excludedClasses
        + ", excludedClassesFile="
        + excludedClassesFile
        + ", excludedClassLoaders="
        + excludedClassLoaders
        + ", excludedCodeSources="
        + excludedCodeSources
        + ", deferredClassLoaders="
        + deferredClassLoaders
        + ", deferIntegrationsUntil="
        + deferIntegrationsUntil
        + ", resolverCacheConfig="
        + resolverCacheConfig
        + ", resolverCacheDir="
        + resolverCacheDir
        + ", resolverNamesAreUnique="
        + resolverNamesAreUnique
        + ", resolverSimpleMethodGraph="
        + resolverSimpleMethodGraph
        + ", resolverUseLoadClass="
        + resolverUseLoadClass
        + ", resolverUseUrlCaches="
        + resolverUseUrlCaches
        + ", resolverResetInterval="
        + resolverResetInterval
        + ", runtimeContextFieldInjection="
        + runtimeContextFieldInjection
        + ", serialVersionUIDFieldInjection="
        + serialVersionUIDFieldInjection
        + ", codeOriginEnabled="
        + codeOriginEnabled
        + ", traceAnnotations='"
        + traceAnnotations
        + '\''
        + ", traceAnnotationAsync="
        + traceAnnotationAsync
        + ", traceMethods='"
        + traceMethods
        + '\''
        + ", measureMethods= '"
        + measureMethods
        + '\''
        + ", internalExitOnFailure="
        + internalExitOnFailure
        + ", additionalJaxRsAnnotations="
        + additionalJaxRsAnnotations
        + ", websocketTracingEnabled="
        + websocketTracingEnabled
        + ", pekkoSchedulerEnabled="
        + pekkoSchedulerEnabled
        + ", rumEnabled="
        + rumEnabled
        + '}';
  }
}
