plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")
apply(from = "$rootDir/gradle/publish.gradle")

val minimumBranchCoverage by extra(0.8)

// These are tested outside of this module since this module mainly just defines 'API'
val excludedClassesCoverage by extra(
  listOf(
    "datadog.trace.api.ConfigDefaults",
    "datadog.trace.api.CorrelationIdentifier",
    "datadog.trace.api.DDSpanTypes",
    "datadog.trace.api.DDTags",
    "datadog.trace.api.DDTraceApiInfo",
    "datadog.trace.api.DDTraceId",
    "datadog.trace.api.EventTracker",
    "datadog.trace.api.GlobalTracer*",
    "datadog.trace.api.PropagationStyle",
    "datadog.trace.api.TracePropagationStyle",
    "datadog.trace.api.TracePropagationBehaviorExtract",
    "datadog.trace.api.SpanCorrelation*",
    "datadog.trace.api.internal.TraceSegment",
    "datadog.trace.api.internal.TraceSegment.NoOp",
    "datadog.trace.api.aiguard.AIGuard",
    "datadog.trace.api.aiguard.AIGuard.AIGuardAbortError",
    "datadog.trace.api.aiguard.AIGuard.AIGuardClientError",
    "datadog.trace.api.aiguard.AIGuard.Options",
    "datadog.trace.api.civisibility.CIVisibility",
    "datadog.trace.api.civisibility.DDTestModule",
    "datadog.trace.api.civisibility.noop.NoOpDDTest",
    "datadog.trace.api.civisibility.noop.NoOpDDTestModule",
    "datadog.trace.api.civisibility.noop.NoOpDDTestSession",
    "datadog.trace.api.civisibility.noop.NoOpDDTestSuite",
    "datadog.trace.api.config.AIGuardConfig",
    "datadog.trace.api.config.ProfilingConfig",
    "datadog.trace.api.interceptor.MutableSpan",
    "datadog.trace.api.profiling.Profiling",
    "datadog.trace.api.profiling.Profiling.NoOp",
    "datadog.trace.api.profiling.ProfilingScope",
    "datadog.trace.api.profiling.ProfilingContext",
    "datadog.trace.api.profiling.ProfilingContextAttribute.NoOp",
    "datadog.trace.api.llmobs.LLMObs",
    "datadog.trace.api.llmobs.LLMObs.LLMMessage",
    "datadog.trace.api.llmobs.LLMObs.ToolCall",
    "datadog.trace.api.llmobs.LLMObsSpan",
    "datadog.trace.api.llmobs.noop.NoOpLLMObsSpan",
    "datadog.trace.api.llmobs.noop.NoOpLLMObsSpanFactory",
    "datadog.trace.api.llmobs.noop.NoOpLLMObsEvalProcessor",
    "datadog.trace.api.experimental.DataStreamsCheckpointer",
    "datadog.trace.api.experimental.DataStreamsCheckpointer.NoOp",
    "datadog.trace.api.experimental.DataStreamsContextCarrier",
    "datadog.trace.api.experimental.DataStreamsContextCarrier.NoOp",
    "datadog.appsec.api.blocking.*",
    "datadog.appsec.api.user.*",
    "datadog.appsec.api.login.*",
    // Default fallback methods to not break legacy API
    "datadog.trace.context.TraceScope",
    "datadog.trace.context.NoopTraceScope.NoopContinuation",
    "datadog.trace.context.NoopTraceScope",
    "datadog.trace.payloadtags.PayloadTagsData",
    "datadog.trace.payloadtags.PayloadTagsData.PathAndValue",
    "datadog.trace.api.llmobs.LLMObsTags",
  ),
)

description = "dd-trace-api"

dependencies {
  api(libs.slf4j)
  testImplementation(libs.guava)
  testImplementation(project(":utils:test-utils"))
}
