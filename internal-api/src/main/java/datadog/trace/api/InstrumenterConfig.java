package datadog.trace.api;

import static datadog.trace.api.ConfigDefaults.DEFAULT_INTEGRATIONS_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_LOGS_INJECTION_ENABLED;
import static datadog.trace.api.ConfigDefaults.DEFAULT_RESOLVER_OUTLINE_POOL_SIZE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_RESOLVER_TYPE_POOL_SIZE;
import static datadog.trace.api.ConfigDefaults.DEFAULT_RUNTIME_CONTEXT_FIELD_INJECTION;
import static datadog.trace.api.ConfigDefaults.DEFAULT_SERIALVERSIONUID_FIELD_INJECTION;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_ANNOTATIONS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_EXECUTORS_ALL;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_METHODS;
import static datadog.trace.api.config.GeneralConfig.INTERNAL_EXIT_ON_FAILURE;
import static datadog.trace.api.config.TraceInstrumentationConfig.INTEGRATIONS_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.JDBC_CONNECTION_CLASS_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.JDBC_PREPARED_STATEMENT_CLASS_NAME;
import static datadog.trace.api.config.TraceInstrumentationConfig.LOGS_INJECTION_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.RESOLVER_OUTLINE_POOL_ENABLED;
import static datadog.trace.api.config.TraceInstrumentationConfig.RESOLVER_OUTLINE_POOL_SIZE;
import static datadog.trace.api.config.TraceInstrumentationConfig.RESOLVER_TYPE_POOL_SIZE;
import static datadog.trace.api.config.TraceInstrumentationConfig.RESOLVER_USE_LOADCLASS;
import static datadog.trace.api.config.TraceInstrumentationConfig.RUNTIME_CONTEXT_FIELD_INJECTION;
import static datadog.trace.api.config.TraceInstrumentationConfig.SERIALVERSIONUID_FIELD_INJECTION;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_ANNOTATIONS;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_CLASSES_EXCLUDE;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_CLASSES_EXCLUDE_FILE;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_CLASSLOADERS_EXCLUDE;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_CODESOURCES_EXCLUDE;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_EXECUTORS;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_EXECUTORS_ALL;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_METHODS;
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_THREAD_POOL_EXECUTORS_EXCLUDE;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableList;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableSet;

import datadog.trace.bootstrap.config.provider.ConfigProvider;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class InstrumenterConfig {
  private final ConfigProvider configProvider;

  private final boolean integrationsEnabled;

  private final boolean logsInjectionEnabled;

  private final boolean traceExecutorsAll;
  private final List<String> traceExecutors;
  private final Set<String> traceThreadPoolExecutorsExclude;

  private final String jdbcPreparedStatementClassName;
  private final String jdbcConnectionClassName;

  private final List<String> excludedClasses;
  private final String excludedClassesFile;
  private final Set<String> excludedClassLoaders;
  private final List<String> excludedCodeSources;

  private final boolean resolverOutlinePoolEnabled;
  private final int resolverOutlinePoolSize;
  private final int resolverTypePoolSize;
  private final boolean resolverUseLoadClassEnabled;

  private final boolean runtimeContextFieldInjection;
  private final boolean serialVersionUIDFieldInjection;

  private final String traceAnnotations;
  private final String traceMethods;

  private final boolean internalExitOnFailure;

  private InstrumenterConfig() {
    this.configProvider =
        Platform.isIsNativeImageBuilder()
            ? ConfigProvider.newInstance(false)
            : ConfigProvider.getInstance();

    integrationsEnabled =
        configProvider.getBoolean(INTEGRATIONS_ENABLED, DEFAULT_INTEGRATIONS_ENABLED);

    logsInjectionEnabled =
        configProvider.getBoolean(LOGS_INJECTION_ENABLED, DEFAULT_LOGS_INJECTION_ENABLED);

    traceExecutorsAll = configProvider.getBoolean(TRACE_EXECUTORS_ALL, DEFAULT_TRACE_EXECUTORS_ALL);
    traceExecutors = tryMakeImmutableList(configProvider.getList(TRACE_EXECUTORS));
    traceThreadPoolExecutorsExclude =
        tryMakeImmutableSet(configProvider.getList(TRACE_THREAD_POOL_EXECUTORS_EXCLUDE));

    jdbcPreparedStatementClassName =
        configProvider.getString(JDBC_PREPARED_STATEMENT_CLASS_NAME, "");

    jdbcConnectionClassName = configProvider.getString(JDBC_CONNECTION_CLASS_NAME, "");

    excludedClasses = tryMakeImmutableList(configProvider.getList(TRACE_CLASSES_EXCLUDE));
    excludedClassesFile = configProvider.getString(TRACE_CLASSES_EXCLUDE_FILE);
    excludedClassLoaders = tryMakeImmutableSet(configProvider.getList(TRACE_CLASSLOADERS_EXCLUDE));
    excludedCodeSources = tryMakeImmutableList(configProvider.getList(TRACE_CODESOURCES_EXCLUDE));

    resolverOutlinePoolEnabled = configProvider.getBoolean(RESOLVER_OUTLINE_POOL_ENABLED, true);
    resolverOutlinePoolSize =
        configProvider.getInteger(RESOLVER_OUTLINE_POOL_SIZE, DEFAULT_RESOLVER_OUTLINE_POOL_SIZE);
    resolverTypePoolSize =
        configProvider.getInteger(RESOLVER_TYPE_POOL_SIZE, DEFAULT_RESOLVER_TYPE_POOL_SIZE);
    resolverUseLoadClassEnabled = configProvider.getBoolean(RESOLVER_USE_LOADCLASS, true);

    runtimeContextFieldInjection =
        configProvider.getBoolean(
            RUNTIME_CONTEXT_FIELD_INJECTION, DEFAULT_RUNTIME_CONTEXT_FIELD_INJECTION);
    serialVersionUIDFieldInjection =
        configProvider.getBoolean(
            SERIALVERSIONUID_FIELD_INJECTION, DEFAULT_SERIALVERSIONUID_FIELD_INJECTION);

    traceAnnotations = configProvider.getString(TRACE_ANNOTATIONS, DEFAULT_TRACE_ANNOTATIONS);
    traceMethods = configProvider.getString(TRACE_METHODS, DEFAULT_TRACE_METHODS);

    internalExitOnFailure = configProvider.getBoolean(INTERNAL_EXIT_ON_FAILURE, false);
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

  public boolean isLogsInjectionEnabled() {
    return logsInjectionEnabled;
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

  public boolean isResolverOutlinePoolEnabled() {
    return resolverOutlinePoolEnabled;
  }

  public int getResolverOutlinePoolSize() {
    return resolverOutlinePoolSize;
  }

  public int getResolverTypePoolSize() {
    return resolverTypePoolSize;
  }

  public boolean isResolverUseLoadClassEnabled() {
    return resolverUseLoadClassEnabled;
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

  public boolean isLegacyInstrumentationEnabled(
      final boolean defaultEnabled, final String... integrationNames) {
    return configProvider.isEnabled(
        Arrays.asList(integrationNames), "", ".legacy.tracing.enabled", defaultEnabled);
  }

  // This has to be placed after all other static fields to give them a chance to initialize
  @SuppressFBWarnings("SI_INSTANCE_BEFORE_FINALS_ASSIGNED")
  private static final InstrumenterConfig INSTANCE = new InstrumenterConfig();

  public static InstrumenterConfig get() {
    return INSTANCE;
  }

  @Override
  public String toString() {
    return "InstrumenterConfig{"
        + "integrationsEnabled="
        + integrationsEnabled
        + ", logsInjectionEnabled="
        + logsInjectionEnabled
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
        + ", resolverOutlinePoolEnabled="
        + resolverOutlinePoolEnabled
        + ", resolverOutlinePoolSize="
        + resolverOutlinePoolSize
        + ", resolverTypePoolSize="
        + resolverTypePoolSize
        + ", resolverUseLoadClassEnabled="
        + resolverUseLoadClassEnabled
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
        + ", configProvider="
        + configProvider
        + '}';
  }
}
