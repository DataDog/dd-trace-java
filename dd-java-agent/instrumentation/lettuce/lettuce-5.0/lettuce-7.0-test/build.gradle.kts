plugins {
  id("dd-trace-java.module.testing-support")
  id("dd-trace-java.testcontainers")
}

extra["lettuceTestSuites"] = mapOf("lettuce7LatestDepTest" to "7.+")
apply(from = "../lettuce-tests.gradle.kts")
