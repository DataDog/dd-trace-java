plugins {
  `java-library`
  id("com.gradleup.shadow")
}

apply(from = "$rootDir/gradle/java.gradle")

/*
 * Add an addition gradle configuration to be consumed by bootstrap only.
 * "datadog.trace." prefix is required to be excluded from Jacoco instrumentation.
 * See ConfigDefaults.DEFAULT_CIVISIBILITY_JACOCO_PLUGIN_EXCLUDES for more details.
 */
tasks.shadowJar {
  relocate("datadog.environment", "datadog.trace.bootstrap.environment")
}

/*
 * Configure test coverage.
 */
extra.set("minimumInstructionCoverage", 0.7)
val excludedClassesCoverage by extra {
  listOf(
    "datadog.environment.JavaVirtualMachine", // depends on OS and JVM vendor
    "datadog.environment.JavaVirtualMachine.JvmOptionsHolder", // depends on OS and JVM vendor
    "datadog.environment.JvmOptions", // depends on OS and JVM vendor
    "datadog.environment.OperatingSystem**", // depends on OS
  )
}
val excludedClassesBranchCoverage by extra {
  listOf("datadog.environment.CommandLine") // tested using forked process
}

dependencies {
  implementation(libs.slf4j)

  testImplementation("com.squareup.okhttp3:mockwebserver:${libs.versions.okhttp.legacy.get()}")
}
