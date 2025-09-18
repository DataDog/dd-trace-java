plugins {
  id("java-library")
}

apply(from = "$rootDir/gradle/java.gradle")

dependencies {

  implementation(project(":internal-api"))
  compileOnly(project(":communication"))

  implementation(libs.slf4j)
  api(libs.okhttp)
  api(libs.moshi)

  testImplementation(project(":communication"))

}
