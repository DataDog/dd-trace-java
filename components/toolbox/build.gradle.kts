plugins {
  id("dd-trace-java.module.platform-component")
}

description = "Dependency-free performance primitives: flat hashtables, chunked buffers, hashing."

// The thresholds these classes had in internal-api, so moving them doesn't change what's enforced.
extra["minimumBranchCoverage"] = 0.7
extra["minimumInstructionCoverage"] = 0.8

dependencies {
  testImplementation(libs.bundles.junit5)
}
