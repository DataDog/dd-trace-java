plugins {
  `java-library`
  id("dd-trace-java.module.internal-component")
}

dependencies {
  testImplementation(project(":utils:test-utils"))
}
