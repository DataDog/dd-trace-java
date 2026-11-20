plugins {
  id("dd-trace-java.module.testing-support")
  id("dd-trace-java.instrumentation.lettuce-tests")
}

lettuceTests {
  suite("lettuce8LatestDepTest", "8.+")
  // Resolves the same version as lettuce8LatestDepTest until Lettuce 9 is released
  suite("latestDepTest", "+")
}
