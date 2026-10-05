plugins {
  `java-library`
  id("dd-trace-java.module.internal-api")
}

description = "Feature flagging configuration keys and source resolution"

dependencies {
  testImplementation(libs.bundles.junit5)
}
