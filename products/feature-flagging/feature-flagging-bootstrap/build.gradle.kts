plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")
apply(from = "$rootDir/gradle/version.gradle")

description = "Feature flagging remote common module (bootstrap classloader)"

val excludedClassesCoverage by extra(
  listOf(
    // Feature lags POJOs
    "datadog.trace.api.featureflag.exposure.Allocation",
    "datadog.trace.api.featureflag.exposure.ExposureEvent",
    "datadog.trace.api.featureflag.exposure.ExposuresRequest",
    "datadog.trace.api.featureflag.exposure.Flag",
    "datadog.trace.api.featureflag.exposure.Subject",
    "datadog.trace.api.featureflag.exposure.Variant",
    "datadog.trace.api.featureflag.ufc.v1.Allocation",
    "datadog.trace.api.featureflag.ufc.v1.ConditionConfiguration",
    "datadog.trace.api.featureflag.ufc.v1.ConditionOperator",
    "datadog.trace.api.featureflag.ufc.v1.Environment",
    "datadog.trace.api.featureflag.ufc.v1.Flag",
    "datadog.trace.api.featureflag.ufc.v1.Rule",
    "datadog.trace.api.featureflag.ufc.v1.ServerConfiguration",
    "datadog.trace.api.featureflag.ufc.v1.Shard",
    "datadog.trace.api.featureflag.ufc.v1.ShardRange",
    "datadog.trace.api.featureflag.ufc.v1.Split",
    "datadog.trace.api.featureflag.ufc.v1.ValueType",
    "datadog.trace.api.featureflag.ufc.v1.Variant",
  )
)

dependencies {
  testImplementation(project(":utils:test-utils"))
}
