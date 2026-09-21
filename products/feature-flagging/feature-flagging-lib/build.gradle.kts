plugins {
  `java-library`
  id("dd-trace-java.version-file")
  id("dd-trace-java.module.product-library")
  id("me.champeau.jmh")
}

description = "Shared Feature Flags evaluation, configuration, lifecycle, and event delivery."

// The application adapter and injected helpers need evaluation, not the full runtime. Agent JAR
// indexing routes whole packages, so keep parser and HTTP classes in the product subsystem.
val evaluatorJar = tasks.register<Jar>("evaluatorJar") {
  archiveClassifier = "evaluator"
  from(sourceSets.main.get().output)
  include("com/datadog/featureflag/core/**")
}
configurations.consumable("evaluatorElements") {
  outgoing.artifact(evaluatorJar)
}

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
  api(project(":utils:queue-utils")) { isTransitive = false }
  implementation(project(":components:environment"))
  implementation(project(":products:feature-flagging:feature-flagging-config"))
  implementation(project(":internal-api"))
  implementation(project(":communication"))
  implementation(project(":utils:logging-utils"))

  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
  testImplementation(project(":utils:test-utils"))
  testImplementation(project(":dd-java-agent:testing"))
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
