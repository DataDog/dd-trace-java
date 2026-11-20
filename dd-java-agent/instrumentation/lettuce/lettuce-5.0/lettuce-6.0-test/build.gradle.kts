plugins {
  id("dd-trace-java.module.testing-support")
  id("dd-trace-java.instrumentation.lettuce-tests")
}

lettuceTests {
  suite("lettuce60Test", "6.0.0.RELEASE")
  suite("lettuce61Test", "6.1.0.RELEASE")
  suite("lettuce62Test", "6.2.0.RELEASE")
  suite("lettuce6LatestDepTest", "6.+")
}
