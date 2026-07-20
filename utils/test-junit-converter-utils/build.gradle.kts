plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

dependencies {
  implementation(project(":dd-trace-api"))
  implementation(project(":internal-api"))

  compileOnly(libs.junit.jupiter)
  compileOnly(libs.tabletest)
}
