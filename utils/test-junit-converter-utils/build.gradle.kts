plugins {
  `java-library`
  id("dd-trace-java.module.internal-library")
}

dependencies {
  implementation(project(":dd-trace-api"))
  implementation(project(":internal-api"))

  compileOnly(libs.junit.jupiter)
  compileOnly(libs.tabletest)
}
