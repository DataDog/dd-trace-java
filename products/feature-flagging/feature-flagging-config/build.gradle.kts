plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

description = "Feature flagging configuration keys (compile-time constants)"

extra["excludedClassesCoverage"] = listOf(
  // Constants-only holder — no executable logic to cover.
  "datadog.trace.api.featureflag.config.FeatureFlaggingConfig",
)
