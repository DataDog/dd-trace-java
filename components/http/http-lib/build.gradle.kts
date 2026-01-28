plugins {
  `java-library`
  idea
}

apply(from = "$rootDir/gradle/java.gradle")

description = "HTTP Client Library"

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(11)
  }
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
  api(project(":components:http:http-api"))
  testImplementation(testFixtures(project(":components:http:http-api")))
}
