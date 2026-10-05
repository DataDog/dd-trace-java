plugins {
  `java-library`
  id("dd-trace-java.module.internal-library")
}

dependencies {
  api(libs.forbiddenapis)
  api(project(":components:environment"))

  compileOnly(libs.junit.jupiter)
}
