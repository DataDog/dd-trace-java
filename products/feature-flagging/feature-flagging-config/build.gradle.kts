plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

description = "Feature flagging configuration keys and source resolution"

dependencies {
  testImplementation(libs.bundles.junit5)
}
