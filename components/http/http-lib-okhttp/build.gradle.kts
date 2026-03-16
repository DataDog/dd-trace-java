plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

description = "HTTP Client Library - OkHttp Implementation"

dependencies {
  api(project(":components:http:http-api"))
  implementation(libs.okhttp)
  implementation(project(":utils:socket-utils"))
  testImplementation(testFixtures(project(":components:http:http-api")))
}
