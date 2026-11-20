plugins {
  id("dd-trace-java.module.testing-support")
  id("dd-trace-java.instrumentation.lettuce-tests")
}

lettuceTests {
  suite("lettuce70Test", "7.0.0.RELEASE")
  suite("lettuce7LatestDepTest", "7.+")
}
