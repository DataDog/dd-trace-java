package datadog.buildlogic.jdbc

import java.util.function.BiConsumer
import java.util.function.Consumer

/** Declares the test suites running the shared JDBC instrumentation tests. */
open class JdbcTestsExtension(
  private val registerSuite: BiConsumer<String, Boolean>,
  private val addLatestDependencies: Consumer<String>,
) {
  /**
   * Registers the [name] test suite, running the shared JDBC tests.
   *
   * Without [withForkedTest], the `*ForkedTest` classes don't run for this suite.
   */
  fun suite(
    name: String,
    withForkedTest: Boolean = true,
  ) {
    registerSuite.accept(name, withForkedTest)
  }

  /**
   * Registers the [name] test suite, running the shared JDBC tests against the latest drivers and
   * connection pools still supporting Java 8.
   */
  fun latestDepSuite(name: String) {
    registerSuite.accept(name, true)
    addLatestDependencies.accept(name)
  }
}
