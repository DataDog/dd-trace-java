plugins {
  `java-library`
  id("supported-config-generator")
}

dependencies {
  implementation(project(":components:environment"))
  implementation(project(":dd-trace-api"))
  implementation("org.snakeyaml", "snakeyaml-engine", "2.9")
}

apply(from = "$rootDir/gradle/java.gradle")
