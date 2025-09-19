plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

dependencies {
  implementation(project(":components:environment"))
  implementation(project(":components:yaml"))
  implementation(project(":dd-trace-api"))
  implementation(libs.slf4j)

  testImplementation(project(":utils:test-utils"))
}

tasks.named<ProcessResources>("processResources") {
  exclude("supported-configurations.json")
}

tasks.named<Jar>("jar") {
  exclude("supported-configurations.json")
}
