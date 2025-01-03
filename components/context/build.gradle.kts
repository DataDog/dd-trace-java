plugins {
  id("me.champeau.jmh")
}

apply(from = "$rootDir/gradle/java.gradle")

jmh {
  version = "1.28"
}

val excludedClassesInstructionCoverage by extra {
  listOf("datadog.context.ContextProviders") // covered by forked test
}

tasks.test {
  systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
}
