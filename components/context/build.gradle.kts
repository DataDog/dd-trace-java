apply(from = "$rootDir/gradle/java.gradle")

val excludedClassesInstructionCoverage by extra {
  listOf("datadog.context.ContextProviders") // covered by forked test
}
