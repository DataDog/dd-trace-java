plugins {
  `java-library`
  `java-test-fixtures`
  id("dd-trace-java.module.platform-component")
}

description = "HTTP Client API"

extra["minimumBranchCoverage"] = 0 // extra(0.7) -- need a library implementation
extra["minimumInstructionCoverage"] = 0 // extra(0.7) -- need a library implementation

// Exclude interfaces for test coverage
extra["excludedClassesCoverage"] = listOf(
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

dependencies {
  // Add API implementations to test providers
  // testRuntimeOnly(project(":components:http:http-lib-jdk"))
  // testRuntimeOnly(project(":components:http:http-lib-okhttp"))
  // Add MockServer for test fixtures
  // Avoid mockserver-junit-jupiter-no-dependencies: its embedded JUnit classes can shadow ours.
  testFixturesImplementation(libs.junit.jupiter)
  // DO NOT BUMP THIS VERSION WITHOUT CHECKING THE JAR CONTENTS.
  //
  // The `-no-dependencies` artifacts relocate most of their dependencies under `shaded_package`,
  // but leave `org.slf4j` unrelocated. That jar sorts ahead of `slf4j-api` on the test runtime
  // classpath, so whichever slf4j API it embeds is the one that gets loaded:
  //   * 5.14.0 embeds the slf4j 1.7 API, which matches the slf4j-api version used here, and
  //     ships no binding of its own, so logback-classic still binds normally.
  //   * 5.15.0 embeds the slf4j 2.0 API plus a
  //     `META-INF/services/org.slf4j.spi.SLF4JServiceProvider` pointing at
  //     `org.slf4j.jul.JULServiceProvider`. slf4j 2.0 ignores logback 1.2's
  //     `org.slf4j.impl.StaticLoggerBinder`, so test logging silently reroutes to JUL.
  //
  // Switching to the non-shaded `org.mock-server:mockserver-netty` would remove the hazard
  // entirely by letting Gradle arbitrate slf4j, at the cost of many transitives.
  testFixturesImplementation("org.mock-server:mockserver-netty-no-dependencies:5.14.0")
}
