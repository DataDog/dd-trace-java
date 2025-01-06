plugins {
  id("me.champeau.jmh")
}

apply(from = "$rootDir/gradle/java.gradle")

jmh {
  version = "1.28"
}

dependencies {
  testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.0")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.0")
  implementation("org.junit.platform:junit-platform-launcher:1.9.0")
}

val excludedClassesInstructionCoverage by extra {
  listOf("datadog.context.ContextProviders") // covered by forked test
}

tasks.test {
  systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
}
