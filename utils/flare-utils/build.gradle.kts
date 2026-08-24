plugins {
  id("java-library")
  id("dd-trace-java.module.internal-library")
}

dependencies {
  api(libs.okhttp)
  api(libs.moshi)

  compileOnly(project(":communication"))
  implementation(project(":utils:version-utils"))
  implementation(project(":internal-api"))
  implementation(libs.slf4j)
}
