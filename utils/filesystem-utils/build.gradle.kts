plugins {
  `java-library`
  id("dd-trace-java.module.internal-library")
}

dependencies {
  testImplementation(project(":utils:test-utils"))
}
