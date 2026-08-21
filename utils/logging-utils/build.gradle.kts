import groovy.lang.Closure
import org.gradle.kotlin.dsl.extra

plugins {
  `java-library`
  id("dd-trace-java.module.internal-library")
}

dependencies {
  implementation(libs.slf4j)
  implementation(project(":internal-api"))

  testImplementation(libs.bundles.junit5)
  testImplementation(libs.bundles.mockito)
}
