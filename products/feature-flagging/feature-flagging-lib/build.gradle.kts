plugins {
  `java-library`
  id("dd-trace-java.version-file")
  id("dd-trace-java.module.product-library")
  id("me.champeau.jmh")
}

description = "Shared Feature Flags lifecycle, queues, and event aggregation."

extra["excludedClassesCoverage"] = listOf(
  // POJOs
  "com.datadog.featureflag.ExposureCache.Key",
  "com.datadog.featureflag.ExposureCache.Value",
  // Concrete transport composition is exercised by standalone deployment tests.
  "com.datadog.featureflag.StandaloneFeatureFlaggingSystem.DefaultRuntime"
)

dependencies {
  api(libs.slf4j)
  api(libs.moshi)
  api(libs.jctools)
  api(project(":products:feature-flagging:feature-flagging-bootstrap"))
  api(project(":products:feature-flagging:feature-flagging-core"))
  api(project(":utils:queue-utils")) { isTransitive = false }
  implementation(project(":components:environment"))

  // Platform JSON writer for the ffe_* tag values.

  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
}

jmh {
  jmhVersion = libs.versions.jmh.get()
  duplicateClassesStrategy = DuplicatesStrategy.EXCLUDE
  if (project.hasProperty("jmhIncludes")) {
    includes = listOf(project.property("jmhIncludes").toString())
  }
  if (project.hasProperty("jmhProf")) {
    profilers = listOf(project.property("jmhProf").toString())
  }
}
