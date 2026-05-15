plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

dependencies {
  api(libs.bytebuddy)
  api(libs.bytebuddyagent)
  api(libs.forbiddenapis)
  api(project(":components:environment"))

  compileOnly(libs.junit.jupiter)
  compileOnly(libs.tabletest)
}
