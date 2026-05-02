plugins {
  `java-library`
  `java-test-fixtures`
}

apply(from = "$rootDir/gradle/java.gradle")

description = "HTTP Client API"

val minimumBranchCoverage by extra(0) // extra(0.7) -- need a library implementation
val minimumInstructionCoverage by extra(0) // extra(0.7) -- need a library implementation

// Exclude interfaces for test coverage
val excludedClassesCoverage by extra(
  listOf(
    "datadog.http.client.HttpClient",
    "datadog.http.client.HttpClient.Builder",
    "datadog.http.client.HttpRequest",
    "datadog.http.client.HttpRequest.Builder",
    "datadog.http.client.HttpRequestBody",
    "datadog.http.client.HttpRequestBody.MultipartBuilder",
    "datadog.http.client.HttpRequestListener",
    "datadog.http.client.HttpResponse",
    "datadog.http.client.HttpUrl",
    "datadog.http.client.HttpUrl.Builder",
  )
)

dependencies {
  // Add API implementations to test providers
  // testRuntimeOnly(project(":components:http:http-lib-jdk"))
  // testRuntimeOnly(project(":components:http:http-lib-okhttp"))
  // Add MockServer for test fixtures
  testFixturesImplementation("org.mock-server:mockserver-junit-jupiter-no-dependencies:5.14.0")
}
