package datadog.buildlogic.lettuce

import java.util.function.BiConsumer

/** Declares the test suites running the shared Lettuce instrumentation tests. */
open class LettuceTestsExtension(
  private val registerSuite: BiConsumer<String, String>,
) {
  /**
   * Registers the [name] test suite, running the shared Lettuce tests against lettuce-core [version].
   *
   * Suites whose name contains `latest` run in the latest dependency CI jobs only.
   */
  fun suite(
    name: String,
    version: String,
  ) {
    registerSuite.accept(name, version)
  }
}
