plugins {
  id("dd-trace-java.module.platform-component")
}

description = "Dependency-free performance primitives: flat hashtables, chunked buffers, hashing."

dependencies {
  testImplementation(libs.bundles.junit5)
}
