plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

dependencies {
  api(project(":internal-api"))
  api(libs.jctools)
}
