plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

dependencies {
  api(libs.forbiddenapis)
  api(project(":components:environment"))

  compileOnly(libs.junit.jupiter)
}
