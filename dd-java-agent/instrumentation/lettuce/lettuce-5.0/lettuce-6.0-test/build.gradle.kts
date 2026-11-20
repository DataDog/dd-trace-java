plugins {
  id("dd-trace-java.module.testing-support")
  id("dd-trace-java.testcontainers")
}

extra["lettuceTestSuites"] = mapOf(
  "lettuce60Test" to "6.0.0.RELEASE",
  "lettuce61Test" to "6.1.0.RELEASE",
  "lettuce62Test" to "6.2.0.RELEASE",
  "lettuce6LatestDepTest" to "6.+",
)
apply(from = "../lettuce-tests.gradle.kts")
