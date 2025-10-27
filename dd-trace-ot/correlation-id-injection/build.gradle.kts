plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

val minimumBranchCoverage by extra(0.8)

val excludedClassesCoverage by extra(
  listOf(
    "datadog.trace.correlation.CorrelationIdInjectors",
    "datadog.trace.correlation.CorrelationIdInjectors.InjectorType",
  ),
)

description = "correlation-id-injection"

val log4j1 = "1.2.17"
val log4j2 = "2.19.0"
val logback = "1.3.5"

dependencies {
  api(project(":dd-trace-api"))
  implementation(libs.slf4j)
  compileOnly("org.apache.logging.log4j:log4j-api:$log4j2")
  compileOnly("log4j:log4j:$log4j1")

  testImplementation(libs.guava)
  testImplementation(project(":dd-trace-ot"))
  testImplementation(project(":dd-java-agent:testing"))
  testImplementation("org.apache.logging.log4j:log4j-core:$log4j2")
  testImplementation("ch.qos.logback:logback-core:$logback")
}
