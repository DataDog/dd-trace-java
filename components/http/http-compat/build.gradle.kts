plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

description = "HTTP Client Library - Java 8 Compatibility"

dependencies {
  api(project(":components:http:http-api"))
}
