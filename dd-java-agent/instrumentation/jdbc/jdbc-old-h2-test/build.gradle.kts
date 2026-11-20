plugins {
  id("dd-trace-java.module.testing-support")
  id("dd-trace-java.instrumentation.jdbc-tests")
}

// The old driver suites check the regular tests against old drivers, so they don't run the forked tests
jdbcTests {
  suite("oldH2Test", withForkedTest = false)
}

dependencies {
  // Test pre jdk 1.6 H2
  "oldH2TestImplementation"("com.h2database:h2") {
    version {
      strictly("1.3.168")
    }
  }
}
