plugins {
  id("java-library")
}

apply(from = "$rootDir/gradle/java.gradle")

dependencies {
  api(libs.okhttp)
  api(libs.moshi)

  compileOnly(project(":communication"))
  implementation(project(":utils:version-utils"))
  implementation(project(":internal-api"))
  implementation(libs.slf4j)

  testImplementation(project(":utils:test-utils"))
  testImplementation(project(":dd-trace-api"))
}
