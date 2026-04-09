plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

dependencies {
  // For CoverageReportUploader
  implementation(project(":communication"))
  implementation(project(":internal-api"))

  testImplementation(project(":dd-java-agent:testing"))
}
