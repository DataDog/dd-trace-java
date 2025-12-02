plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

dependencies {
  implementation("io.btrace", "jafar-parser", "0.0.1-SNAPSHOT")
  implementation(project(":internal-api"))
  implementation(project(":components:json"))

  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.jmc)
  testImplementation(libs.jmc.flightrecorder.writer)
}
