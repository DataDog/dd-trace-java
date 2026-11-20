plugins {
  id("dd-trace-java.module.testing-support")
  id("dd-trace-java.instrumentation.jdbc-tests")
}

// The old driver suites check the regular tests against old drivers, so they don't run the forked tests
jdbcTests {
  suite("oldPostgresTest", withForkedTest = false)
}

dependencies {
  "oldPostgresTestImplementation"("org.postgresql:postgresql") {
    version {
      strictly("9.4-1201-jdbc41")
    }
  }
}
