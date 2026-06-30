plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

extra["excludedClassesCoverage"] = listOf(
  "datadog.trace.test.logging.*",
  "external.util.stacktrace.*",
  "datadog.trace.test.util.AnyStackRunner*",
  "datadog.trace.test.util.AssertionsUtils",
  "datadog.trace.test.util.CircularBuffer",
  "datadog.trace.test.util.CleanConfigStateExtension",
  "datadog.trace.test.util.DDJavaSpecification",
  "datadog.trace.test.util.ForkedTestUtils",
  "datadog.trace.test.util.GCUtils",
  "datadog.trace.test.util.ThreadUtils*",
  "datadog.trace.test.util.ConfigInstrumentationFailedListener",
  "datadog.trace.test.util.ConfigTransformSpockExtension*",
  "datadog.trace.test.util.ControllableEnvironmentVariables*",
  "datadog.trace.test.util.DDSpecification*",
  "datadog.trace.test.util.Flaky*",
  "datadog.trace.test.util.FlakySpockExtension*",
  "datadog.trace.test.util.MultipartRequestParser*",
  "datadog.trace.test.util.NonRetryable",
)

dependencies {
  api(libs.bytebuddy)
  api(libs.bytebuddyagent)
  api(libs.forbiddenapis)

  api(project(":components:environment"))
  api(project(":utils:config-utils"))
  api(project(":utils:junit-utils"))
  api(group = "commons-fileupload", name = "commons-fileupload", version = "1.5")

  compileOnly(project(":components:annotations"))
  compileOnly(libs.junit.jupiter)
  compileOnly(libs.logback.core)
  compileOnly(libs.logback.classic)

  compileOnly(libs.bundles.groovy)
  compileOnly(libs.bundles.spock)
}
