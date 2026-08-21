plugins {
  `java-library`
  id("dd-trace-java.version-file")
  id("dd-trace-java.module.product-library")
  id("me.champeau.jmh")
}

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
  implementation(project(":internal-api"))
  api(project(":products:feature-flagging:feature-flagging-bootstrap"))
  compileOnly(project(":products:feature-flagging:feature-flagging-config"))
  implementation(project(":utils:logging-utils"))
  api(project(":utils:queue-utils"))

  // Platform JSON writer for the ffe_* tag values.
  compileOnly(project(":components:json"))

  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
  testImplementation(project(":products:feature-flagging:feature-flagging-config"))
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
