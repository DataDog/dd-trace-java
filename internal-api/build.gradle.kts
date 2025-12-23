import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis
import groovy.lang.Closure

plugins {
  `java-library`
  id("me.champeau.jmh")
}

apply(from = "$rootDir/gradle/java.gradle")

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(8)
  }
}

tasks.withType<JavaCompile>().configureEach {
  configureCompiler(8, JavaVersion.VERSION_1_8, "Need access to sun.misc.SharedSecrets")
}

fun AbstractCompile.configureCompiler(javaVersionInteger: Int, compatibilityVersion: JavaVersion? = null, unsetReleaseFlagReason: String? = null) {
  (project.extra["configureCompiler"] as Closure<*>).call(this, javaVersionInteger, compatibilityVersion, unsetReleaseFlagReason)
}

tasks.named<CheckForbiddenApis>("forbiddenApisMain") {
  // sun.* are accessible in JDK8, but maybe not accessible when this task is running
  failOnMissingClasses = false
}

val minimumBranchCoverage by extra(0.7)
val minimumInstructionCoverage by extra(0.8)

val excludedClassesCoverage by extra(
  listOf(
    "datadog.trace.api.ClassloaderConfigurationOverrides",
    "datadog.trace.api.ClassloaderConfigurationOverrides.Lazy",
    // Interface
    "datadog.trace.api.EndpointTracker",
    // Noop implementation
    "datadog.trace.api.NoOpStatsDClient",
    "datadog.trace.api.Platform",
    // Interface
    "datadog.trace.api.StatsDClient",
    // Noop implementation
    "datadog.trace.api.TraceSegment.NoOp",
    "datadog.trace.api.WithGlobalTracer.1",
    "datadog.trace.api.gateway.Events.ET",
    // Noop implementation
    "datadog.trace.api.gateway.RequestContext.Noop",
    // Enum
    "datadog.trace.api.intake.TrackType",
    "datadog.trace.api.naming.**",
    // Enum
    "datadog.trace.api.profiling.ProfilingSnapshot.Kind",
    "datadog.trace.api.sampling.AdaptiveSampler",
    "datadog.trace.api.sampling.ConstantSampler",
    "datadog.trace.api.sampling.SamplingRule.Provenance",
    "datadog.trace.api.sampling.SamplingRule.TraceSamplingRule.TargetSpan",
    "datadog.trace.api.EndpointCheckpointerHolder",
    "datadog.trace.api.iast.IastAdvice.Kind",
    "datadog.trace.api.UserEventTrackingMode",
    // These are almost fully abstract classes so nothing to test
    "datadog.trace.api.profiling.RecordingData",
    "datadog.trace.api.appsec.AppSecEventTracker",
    // POJOs
    "datadog.trace.api.appsec.HttpClientPayload",
    "datadog.trace.api.appsec.HttpClientRequest",
    "datadog.trace.api.appsec.HttpClientResponse",
    // A plain enum
    "datadog.trace.api.profiling.RecordingType",
    // Data Streams Monitoring
    "datadog.trace.api.datastreams.Backlog",
    "datadog.trace.api.datastreams.InboxItem",
    "datadog.trace.api.datastreams.NoopDataStreamsMonitoring",
    "datadog.trace.api.datastreams.NoopPathwayContext",
    "datadog.trace.api.datastreams.SchemaRegistryUsage",
    "datadog.trace.api.datastreams.StatsPoint",
    // Debugger
    "datadog.trace.api.debugger.DebuggerConfigUpdate",
    // Bootstrap API
    "datadog.trace.bootstrap.ActiveSubsystems",
    "datadog.trace.bootstrap.ContextStore.Factory",
    "datadog.trace.bootstrap.instrumentation.api.java.lang.ProcessImplInstrumentationHelpers",
    "datadog.trace.bootstrap.instrumentation.api.Tags",
    "datadog.trace.bootstrap.instrumentation.api.CommonTagValues",
    // Caused by empty 'default' interface method
    "datadog.trace.bootstrap.instrumentation.api.AgentPropagation",
    "datadog.trace.bootstrap.instrumentation.api.AgentPropagation.ContextVisitor",
    "datadog.trace.bootstrap.instrumentation.api.AgentScope",
    "datadog.trace.bootstrap.instrumentation.api.AgentScope.Continuation",
    "datadog.trace.bootstrap.instrumentation.api.AgentSpan",
    "datadog.trace.bootstrap.instrumentation.api.AgentSpanContext",
    "datadog.trace.bootstrap.instrumentation.api.AgentTracer",
    "datadog.trace.bootstrap.instrumentation.api.AgentTracer.NoopAgentHistogram",
    "datadog.trace.bootstrap.instrumentation.api.AgentTracer.NoopAgentTraceCollector",
    "datadog.trace.bootstrap.instrumentation.api.AgentTracer.NoopTraceConfig",
    "datadog.trace.bootstrap.instrumentation.api.AgentTracer.NoopTracerAPI",
    "datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI",
    "datadog.trace.bootstrap.instrumentation.api.BlackHoleSpan",
    "datadog.trace.bootstrap.instrumentation.api.BlackHoleSpan.Context",
    "datadog.trace.bootstrap.instrumentation.api.ErrorPriorities",
    "datadog.trace.bootstrap.instrumentation.api.ExtractedSpan",
    "datadog.trace.bootstrap.instrumentation.api.ImmutableSpan",
    "datadog.trace.bootstrap.instrumentation.api.InstrumentationTags",
    "datadog.trace.bootstrap.instrumentation.api.InternalContextKeys",
    "datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes",
    "datadog.trace.bootstrap.instrumentation.api.NoopAgentScope",
    "datadog.trace.bootstrap.instrumentation.api.NoopAgentSpan",
    "datadog.trace.bootstrap.instrumentation.api.NoopContinuation",
    "datadog.trace.bootstrap.instrumentation.api.NoopScope",
    "datadog.trace.bootstrap.instrumentation.api.NoopSpan",
    "datadog.trace.bootstrap.instrumentation.api.NoopSpanContext",
    "datadog.trace.bootstrap.instrumentation.api.NotSampledSpanContext",
    "datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities",
    "datadog.trace.bootstrap.instrumentation.api.Schema",
    "datadog.trace.bootstrap.instrumentation.api.ScopeSource",
    "datadog.trace.bootstrap.instrumentation.api.ScopedContext",
    "datadog.trace.bootstrap.instrumentation.api.ScopedContextKey",
    "datadog.trace.bootstrap.instrumentation.api.SpanWrapper",
    "datadog.trace.bootstrap.instrumentation.api.TagContext",
    "datadog.trace.bootstrap.instrumentation.api.TagContext.HttpHeaders",
    "datadog.trace.api.civisibility.config.TestIdentifier",
    "datadog.trace.api.civisibility.config.TestFQN",
    "datadog.trace.api.civisibility.config.TestMetadata",
    "datadog.trace.api.civisibility.config.TestSourceData",
    "datadog.trace.api.civisibility.config.LibraryCapability",
    "datadog.trace.api.civisibility.coverage.CoveragePerTestBridge",
    "datadog.trace.api.civisibility.coverage.CoveragePerTestBridge.TotalProbeCount",
    "datadog.trace.api.civisibility.coverage.CoveragePercentageBridge",
    "datadog.trace.api.civisibility.coverage.NoOpCoverageStore",
    "datadog.trace.api.civisibility.coverage.NoOpCoverageStore.Factory",
    "datadog.trace.api.civisibility.coverage.NoOpProbes",
    "datadog.trace.api.civisibility.coverage.TestReport",
    "datadog.trace.api.civisibility.coverage.TestReportFileEntry",
    "datadog.trace.api.civisibility.domain.BuildModuleLayout",
    "datadog.trace.api.civisibility.domain.BuildModuleSettings",
    "datadog.trace.api.civisibility.domain.BuildSessionSettings",
    "datadog.trace.api.civisibility.domain.JavaAgent",
    "datadog.trace.api.civisibility.domain.Language",
    "datadog.trace.api.civisibility.domain.SourceSet",
    "datadog.trace.api.civisibility.domain.SourceSet.Type",
    "datadog.trace.api.civisibility.events.BuildEventsHandler.ModuleInfo",
    "datadog.trace.api.civisibility.events.TestDescriptor",
    "datadog.trace.api.civisibility.events.TestSuiteDescriptor",
    "datadog.trace.api.civisibility.execution.TestStatus",
    "datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric",
    "datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric.IndexHolder",
    "datadog.trace.api.civisibility.telemetry.CiVisibilityDistributionMetric",
    "datadog.trace.api.civisibility.telemetry.CiVisibilityMetricData",
    "datadog.trace.api.civisibility.telemetry.NoOpMetricCollector",
    "datadog.trace.api.civisibility.telemetry.tag.*",
    "datadog.trace.api.civisibility.config.Configurations",
    "datadog.trace.api.civisibility.CiVisibilityWellKnownTags",
    "datadog.trace.api.civisibility.InstrumentationBridge",
    "datadog.trace.api.civisibility.InstrumentationTestBridge",
    // POJO
    "datadog.trace.api.git.GitInfo",
    "datadog.trace.api.git.GitInfoProvider",
    "datadog.trace.api.git.GitInfoProvider.ShaDiscrepancy",
    // POJO
    "datadog.trace.api.git.PersonInfo",
    // POJO
    "datadog.trace.api.git.CommitInfo",
    // POJO
    "datadog.trace.api.git.GitUtils",
    // tested indirectly by dependent modules
    "datadog.trace.api.git.RawParseUtils",
    // tested indirectly by dependent modules
    "datadog.trace.api.Config",
    "datadog.trace.api.Config.HostNameHolder",
    "datadog.trace.api.Config.RuntimeIdHolder",
    "datadog.trace.api.DynamicConfig",
    "datadog.trace.api.DynamicConfig.Builder",
    "datadog.trace.api.DynamicConfig.Snapshot",
    "datadog.trace.api.InstrumenterConfig",
    "datadog.trace.api.ResolverCacheConfig.*",
    "datadog.trace.api.logging.intake.LogsIntake",
    "datadog.trace.logging.LoggingSettingsDescription",
    "datadog.trace.util.AgentProxySelector",
    "datadog.trace.util.AgentTaskScheduler",
    "datadog.trace.util.AgentTaskScheduler.PeriodicTask",
    "datadog.trace.util.AgentTaskScheduler.ShutdownHook",
    "datadog.trace.util.AgentThreadFactory",
    "datadog.trace.util.AgentThreadFactory.1",
    "datadog.trace.util.CollectionUtils",
    "datadog.trace.util.ComparableVersion",
    "datadog.trace.util.ComparableVersion.BigIntegerItem",
    "datadog.trace.util.ComparableVersion.IntItem",
    "datadog.trace.util.ComparableVersion.ListItem",
    "datadog.trace.util.ComparableVersion.LongItem",
    "datadog.trace.util.ComparableVersion.StringItem",
    "datadog.trace.util.ConcurrentEnumMap",
    "datadog.trace.util.JPSUtils",
    "datadog.trace.util.MethodHandles",
    "datadog.trace.util.PidHelper",
    "datadog.trace.util.PidHelper.Fallback",
    "datadog.trace.util.ProcessUtils",
    "datadog.trace.util.PropagationUtils",
    "datadog.trace.util.UnsafeUtils",
    // can't reliably force same identity hash for different instance to cover branch
    "datadog.trace.api.cache.FixedSizeCache.IdentityHash",
    "datadog.trace.api.cache.FixedSizeWeakKeyCache",
    // Interface with default method
    "datadog.trace.api.StatsDClientManager",
    "datadog.trace.api.iast.Taintable",
    "datadog.trace.api.Stateful",
    "datadog.trace.api.Stateful.1",
    // a stub
    "datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration",
    "datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration.NoOp",
    // debug
    "datadog.trace.api.iast.Taintable.DebugLogger",
    // POJO
    "datadog.trace.api.iast.util.Cookie",
    "datadog.trace.api.iast.util.Cookie.Builder",
    "datadog.trace.api.telemetry.Endpoint",
    "datadog.trace.api.telemetry.Endpoint.Method",
    "datadog.trace.api.telemetry.LogCollector.RawLogMessage",
    "datadog.trace.api.telemetry.MetricCollector.DistributionSeriesPoint",
    "datadog.trace.api.telemetry.MetricCollector",
    // Enum
    "datadog.trace.api.telemetry.WafTruncatedType",
    // stubs
    "datadog.trace.api.profiling.Timing.NoOp",
    "datadog.trace.api.profiling.Timer.NoOp",
    "datadog.trace.api.profiling.Timer.TimerType",
    // tested in agent-logging
    "datadog.trace.logging.LogLevel",
    "datadog.trace.logging.GlobalLogLevelSwitcher",
    // POJO
    "datadog.trace.util.stacktrace.StackTraceBatch",
    "datadog.trace.util.stacktrace.StackTraceEvent",
    "datadog.trace.util.stacktrace.StackTraceFrame",
    "datadog.trace.api.iast.VulnerabilityMarks",
    "datadog.trace.api.iast.securitycontrol.SecurityControlHelper",
    "datadog.trace.api.iast.securitycontrol.SecurityControl",
    // Trivial holder and no-op
    "datadog.trace.bootstrap.instrumentation.api.SpanPostProcessor.Holder",
    "datadog.trace.bootstrap.instrumentation.api.SpanPostProcessor.NoOpSpanPostProcessor",
    "datadog.trace.util.TempLocationManager",
    "datadog.trace.util.TempLocationManager.*",
  )
)

val excludedClassesBranchCoverage by extra(
  listOf(
    "datadog.trace.api.ProductActivationConfig",
    "datadog.trace.api.ClassloaderConfigurationOverrides.Lazy",
    "datadog.trace.util.stacktrace.HotSpotStackWalker",
    "datadog.trace.util.stacktrace.StackWalkerFactory",
    "datadog.trace.util.TempLocationManager",
    "datadog.trace.util.TempLocationManager.*",
    // Branches depend on RUM injector state that cannot be reliably controlled in unit tests
    "datadog.trace.api.rum.RumInjectorMetrics",
  )
)

val excludedClassesInstructionCoverage by extra(
  listOf(
    "datadog.trace.util.stacktrace.StackWalkerFactory"
  )
)

dependencies {
  // references TraceScope and Continuation from public api
  api(project(":dd-trace-api"))
  api(libs.slf4j)
  api(project(":components:context"))
  api(project(":components:environment"))
  api(project(":components:json"))
  api(project(":components:yaml"))
  api(project(":utils:config-utils"))
  api(project(":utils:time-utils"))

  // has to be loaded by system classloader:
  // it contains annotations that are also present in the instrumented application classes
  api("com.datadoghq:dd-javac-plugin-client:0.2.2")

  testImplementation("org.snakeyaml:snakeyaml-engine:2.9")
  testImplementation(project(":utils:test-utils"))
  testImplementation(libs.bundles.junit5)
  testImplementation("org.junit.vintage:junit-vintage-engine:${libs.versions.junit5.get()}")
  testImplementation(libs.commons.math)
  testImplementation(libs.bundles.mockito)
}

jmh {
  jmhVersion = libs.versions.jmh.get()
  duplicateClassesStrategy = DuplicatesStrategy.EXCLUDE
}
