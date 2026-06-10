plugins {
  `java-library`
  id("dd-trace-java.module.internal-component")
}

dependencies {
  implementation(project(":utils:config-utils"))
  implementation(libs.slf4j)

  testImplementation(project(":utils:test-utils"))
}
