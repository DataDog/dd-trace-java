plugins {
  `java-library`
  id("dd-trace-java.module.internal-library")
  id("dd-trace-java.conventions.testing.groovy")
}

dependencies {
  implementation(project(":utils:config-utils"))
  implementation(libs.slf4j)

  testImplementation(project(":utils:test-utils"))
}
