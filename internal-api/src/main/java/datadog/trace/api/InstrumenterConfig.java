package datadog.trace.api;

import static datadog.trace.api.ConfigDefaults.DEFAULT_APPSEC_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_CIVISIBILITY_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_INTEGRATIONS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_MEASURE_METHODS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_RESOLVER_RESET_INTERVAL;
import static datadog.trace.api.ConfigDefaults.DEFAULT_RUNTIME_CONTEXT_FIELD_INJECTION;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SERIALVERSIONUID_FIELD_INJECTION;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TELEMETRY_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_128_BIT_TRACEID_LOGGING_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_ANNOTATIONS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_ANNOTATION_ASYNC;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_EXECUTORS_ALL;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_METHODS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_OTEL_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_USM_ENABLED;
import static datadog.trace.api.config.AppSecConfig.APPSEC_ENABLED;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_ENABLED;
import static datadog.trace.api.config.GeneralConfig.INTERNAL_EXIT_ON_FAILURE;
import static datadog.trace.api.config.GeneralConfig.TELEMETRY_ENABLED;
import static datadog.trace.api.config.GeneralConfig.TRACE_DEBUG;
import static datadog.trace.api.config.GeneralConfig.TRACE_TRIAGE;
import static datadog.trace.api.config.GeneralConfig.TRIAGE_REPORT_TRIGGER;
import static datadog.trace.api.config.IastConfig.IAST_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DIRECT_ALLOCATION_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DIRECT_ALLOCATION_ENABLED_DEFAULT;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_ENABLED_DEFAULT;
import static datadog.trace.api.config.TraceInstrumentationConfig.AXIS_TRANSPORT_CLASS_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_URL_CONNECTION_CLASS_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.INTEGRATIONS_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.JAX_RS_ADDITIONAL_ANNOTATIONS;
import static datadog.trace.api.config.TraceInstrumentationConfig.JDBC_CONNECTION_CLASS_NAME;
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
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_128_BIT_TRACEID_LOGGING_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_ANNOTATIONS;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_ANNOTATION_ASYNC;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_CLASSES_EXCLUDE;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_CLASSES_EXCLUDE_FILE;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_CLASSLOADERS_EXCLUDE;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_CODESOURCES_EXCLUDE;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_EXECUTORS;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_EXECUTORS_ALL;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_METHODS;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_OTEL_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_THREAD_POOL_EXECUTORS_EXCLUDE;
import static datadog.trace.api.config.UsmConfig.USM_ENABLED;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableList;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableSet;

import datadog.trace.bootstrap.config.provider.ConfigProvider;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
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

  private final boolean traceEnabled;
  private final boolean traceOtelEnabled;
  private final boolean logs128bTraceIdEnabled;
  private final boolean profilingEnabled;
  private final boolean ciVisibilityEnabled;
  private final ProductActivation appSecActivation;
  private final ProductActivation iastActivation;
  private final boolean usmEnabled;
  private final boolean telemetryEnabled;

  private final boolean traceExecutorsAll;
  private final List<String> traceExecutors;
  private final Set<String> traceThreadPoolExecutorsExclude;

  private final String jdbcPreparedStatementClassName;
  private final String jdbcConnectionClassName;

  private final String httpURLConnectionClassName;
  private final String axisTransportClassName;

  private final boolean directAllocationProfilingEnabled;

  private final List<String> excludedClasses;
  private final String excludedClassesFile;
  private final Set<String> excludedClassLoaders;
  private final List<String> excludedCodeSources;

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

    traceEnabled = configProvider.getBoolean(TRACE_ENABLED, DEFAULT_TRACE_ENABLED);
    traceOtelEnabled = configProvider.getBoolean(TRACE_OTEL_ENABLED, DEFAULT_TRACE_OTEL_ENABLED);
    logs128bTraceIdEnabled =
        configProvider.getBoolean(
            TRACE_128_BIT_TRACEID_LOGGING_ENABLED, DEFAULT_TRACE_128_BIT_TRACEID_LOGGING_ENABLED);
    profilingEnabled = configProvider.getBoolean(PROFILING_ENABLED, PROFILING_ENABLED_DEFAULT);

    if (!Platform.isNativeImageBuilder()) {
      ciVisibilityEnabled =
          configProvider.getBoolean(CIVISIBILITY_ENABLED, DEFAULT_CIVISIBILITY_ENABLED);
      appSecActivation =
          ProductActivation.fromString(
              configProvider.getStringNotEmpty(APPSEC_ENABLED, DEFAULT_APPSEC_ENABLED));
      iastActivation =
          ProductActivation.fromString(
              configProvider.getStringNotEmpty(IAST_ENABLED, DEFAULT_IAST_ENABLED));
      usmEnabled = configProvider.getBoolean(USM_ENABLED, DEFAULT_USM_ENABLED);
      telemetryEnabled = configProvider.getBoolean(TELEMETRY_ENABLED, DEFAULT_TELEMETRY_ENABLED);
    } else {
      // disable these features in native-image
      ciVisibilityEnabled = false;
      appSecActivation = ProductActivation.FULLY_DISABLED;
      iastActivation = ProductActivation.FULLY_DISABLED;
      telemetryEnabled = false;
      usmEnabled = false;
    }

    traceExecutorsAll = configProvider.getBoolean(TRACE_EXECUTORS_ALL, DEFAULT_TRACE_EXECUTORS_ALL);
    traceExecutors = tryMakeImmutableList(configProvider.getList(TRACE_EXECUTORS));
    traceThreadPoolExecutorsExclude =
        tryMakeImmutableSet(configProvider.getList(TRACE_THREAD_POOL_EXECUTORS_EXCLUDE));

    jdbcPreparedStatementClassName =
        configProvider.getString(JDBC_PREPARED_STATEMENT_CLASS_NAME, "");
    jdbcConnectionClassName = configProvider.getString(JDBC_CONNECTION_CLASS_NAME, "");

    httpURLConnectionClassName = configProvider.getString(HTTP_URL_CONNECTION_CLASS_NAME, "");
    axisTransportClassName = configProvider.getString(AXIS_TRANSPORT_CLASS_NAME, "");

    directAllocationProfilingEnabled =
        configProvider.getBoolean(
            PROFILING_DIRECT_ALLOCATION_ENABLED, PROFILING_DIRECT_ALLOCATION_ENABLED_DEFAULT);

    excludedClasses = tryMakeImmutableList(configProvider.getList(TRACE_CLASSES_EXCLUDE));
    excludedClassesFile = configProvider.getString(TRACE_CLASSES_EXCLUDE_FILE);
    excludedClassLoaders = tryMakeImmutableSet(configProvider.getList(TRACE_CLASSLOADERS_EXCLUDE));
    excludedCodeSources = tryMakeImmutableList(configProvider.getList(TRACE_CODESOURCES_EXCLUDE));

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
  }

  public boolean isTriageEnabled() {
    return triageEnabled;
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

  public boolean isDataJobsEnabled() {
    // there's no dedicated flag to enabled DJM, it's enough to just enable
    // spark instrumentation
    return isIntegrationEnabled(Collections.singletonList("spark"), false);
  }

  public boolean isTraceEnabled() {
    return traceEnabled;
  }

  public boolean isTraceOtelEnabled() {
    return traceOtelEnabled;
  }

  public boolean isLogs128bTraceIdEnabled() {
    return logs128bTraceIdEnabled;
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

  public ProductActivation getIastActivation() {
    return iastActivation;
  }

  public boolean isUsmEnabled() {
    return usmEnabled;
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

  public String getHttpURLConnectionClassName() {
    return httpURLConnectionClassName;
  }

  public String getAxisTransportClassName() {
    return axisTransportClassName;
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
        + ", logs128bTraceIdEnabled="
        + logs128bTraceIdEnabled
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
        + '}';
  }
}
