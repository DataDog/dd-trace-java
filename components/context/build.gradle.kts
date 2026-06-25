apply(from = "$rootDir/gradle/java.gradle")

extra["excludedClassesInstructionCoverage"] = listOf("datadog.context.ContextProviders") // covered by forked test
