plugins {
  id("dd-trace-java.module.instrumentation")
  id("dd-trace-java.instrumentation.netty-concurrent-tests")
  id("dd-trace-java.instrumentation.lettuce-tests")
  id("dd-trace-java.jmh-conventions")
}

muzzle {
  pass {
    group = "io.lettuce"
    module = "lettuce-core"
    versions = "[5.0.0.RELEASE,)"
    assertInverse = true
  }
}

// The nested *-test modules run the same tests against later Lettuce versions
lettuceTests {
  suite("lettuce51Test", "5.1.0.RELEASE")
  suite("lettuce5LatestDepTest", "5.+")
}

dependencies {
  compileOnly("io.lettuce:lettuce-core:5.0.0.RELEASE")
  add("jmh", "io.lettuce:lettuce-core:5.0.0.RELEASE")
  testImplementation("io.lettuce:lettuce-core:5.0.0.RELEASE")
}
