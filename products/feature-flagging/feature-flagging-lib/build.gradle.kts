plugins {
  `java-library`
  id("dd-trace-java.version-file")
}

apply(from = "$rootDir/gradle/java.gradle")

description = "Feature flagging remote config and exposure handling"

extra["excludedClassesCoverage"] = listOf(
  // POJOs
  "com.datadog.featureflag.ExposureCache.Key",
  "com.datadog.featureflag.ExposureCache.Value"
)

dependencies {
  api(libs.slf4j)
  api(libs.moshi)
  api(libs.jctools)
  api(project(":communication"))
  api(project(":products:feature-flagging:feature-flagging-bootstrap"))
  api(project(":utils:queue-utils"))

  compileOnly(project(":dd-trace-core")) // shading does not work with this one
  // Span-enrichment write tier: TraceInterceptor / GlobalTracer / AgentTracer / AgentSpan. This is
  // an agent-side module (shaded into the agent, never published), so depending on the tracer's
  // internal API is expected — unlike the published dd-openfeature product API.
  compileOnly(project(":internal-api"))

  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
  testImplementation(project(":internal-api"))
  testImplementation(project(":utils:test-utils"))
  testImplementation(project(":dd-java-agent:testing"))
}
