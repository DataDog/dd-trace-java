plugins {
  `java-library`
  `java-test-fixtures`
}

apply(from = "$rootDir/gradle/java.gradle")

description = "HTTP Client API"


dependencies {
  // Add API implementations to test providers
  testRuntimeOnly(project(":components:http:http-compat"))
  testRuntimeOnly(project(":components:http:http-lib"))
  // Add MockServer for test fixtures
  testFixturesImplementation("org.mock-server:mockserver-junit-jupiter-no-dependencies:5.14.0")
}
