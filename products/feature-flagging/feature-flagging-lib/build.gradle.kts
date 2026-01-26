plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")
apply(from = "$rootDir/gradle/version.gradle")

description = "Feature flagging remote config and exposure handling"

val excludedClassesCoverage by extra(
  listOf(
    // POJOs
    "com.datadog.featureflag.ExposureCache.Key",
    "com.datadog.featureflag.ExposureCache.Value"
  )
)

dependencies {
  api(libs.slf4j)
  api(libs.moshi)
  api(libs.jctools)
  api(project(":communication"))
  api(project(":products:feature-flagging:feature-flagging-bootstrap"))
  api(project(":utils:queue-utils"))

  compileOnly(project(":dd-trace-core")) // shading does not work with this one

  testImplementation(project(":utils:test-utils"))
  testImplementation(project(":dd-java-agent:testing"))
}
