plugins {
  id("dd-trace-java.module.instrumentation")
  id("dd-trace-java.instrumentation.netty-concurrent-tests")
  id("dd-trace-java.jmh-conventions")
  id("dd-trace-java.testcontainers")
}

muzzle {
  pass {
    group = "io.lettuce"
    module = "lettuce-core"
    versions = "[5.0.0.RELEASE,)"
    assertInverse = true
  }
}

extra["lettuceTestSuites"] = mapOf(
  "lettuce51Test" to "5.1.0.RELEASE",
  "lettuce5LatestDepTest" to "5.+",
)
apply(from = "lettuce-tests.gradle.kts")

dependencies {
  compileOnly("io.lettuce:lettuce-core:5.0.0.RELEASE")
  add("jmh", "io.lettuce:lettuce-core:5.0.0.RELEASE")
  testImplementation("io.lettuce:lettuce-core:5.0.0.RELEASE")
}
