plugins {
  `java-library`
  id("dd-trace-java.module.internal-api")
  id("dd-trace-java.jmh-conventions")
}

description = "Metrics API"

dependencies {
  implementation(libs.slf4j)
  api(project(":components:environment"))

  testImplementation(libs.bundles.junit5)
  testImplementation(libs.jol.core)
}

jmh {
  jmhVersion = libs.versions.jmh.get()
  duplicateClassesStrategy = DuplicatesStrategy.EXCLUDE
}
