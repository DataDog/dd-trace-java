plugins {
  `java-library`
  id("dd-trace-java.module.internal-component")
}

dependencies {
  api(libs.forbiddenapis)
  api(project(":components:environment"))

  implementation(project(":dd-trace-api"))
  implementation(project(":internal-api"))

  compileOnly(libs.junit.jupiter)
  compileOnly(libs.tabletest)
}
