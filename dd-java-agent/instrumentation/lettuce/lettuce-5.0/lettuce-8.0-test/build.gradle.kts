plugins {
  id("dd-trace-java.module.testing-support")
  id("dd-trace-java.testcontainers")
}

extra["lettuceTestSuites"] = mapOf("latestDepTest" to "+")
apply(from = "../lettuce-tests.gradle.kts")
