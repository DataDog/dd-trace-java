plugins {
  `java-library`
  `java-test-fixtures`
}

apply(from = "$rootDir/gradle/java.gradle")

description = "HTTP Client API"


dependencies {
  testFixturesImplementation("org.mock-server:mockserver-junit-jupiter-no-dependencies:5.14.0")
}
