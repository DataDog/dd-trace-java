package datadog.gradle.plugin.testing

import org.gradle.api.tasks.testing.Test

/** Tolerates reported test failures for flaky CI jobs without reusing or caching their results. */
fun Test.configureTestFailurePolicy() {
  val tolerateFailures = project.providers.environmentVariable("CONTINUE_ON_FAILURE")
    .map { it == "true" }.orElse(false)

  // A normal run must not reuse execution history produced while failures were tolerated.
  inputs.property("ciTolerateTestFailures", tolerateFailures)
  outputs.upToDateWhen { !tolerateFailures.get() }
  outputs.doNotCacheIf("CI tolerates test failures") { tolerateFailures.get() }

  doFirst("configureTestFailurePolicy") {
    (this as Test).ignoreFailures = tolerateFailures.get()
  }
}
