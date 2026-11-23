plugins {
  `java-library`
  id("dd-trace-java.version-file")
  id("dd-trace-java.module.bootstrap-component")
}

description = "Feature flagging bridge between the SDK instrumentation and the agent (bootstrap classloader)"

dependencies {
  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
  testImplementation(project(":utils:test-utils"))
}
