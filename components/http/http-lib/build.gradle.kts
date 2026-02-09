plugins {
  `java-library`
  idea
  id("dd-trace-java.test-jvm-constraints")
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

testJvmConstraints {
  minJavaVersion = JavaVersion.VERSION_11
}
