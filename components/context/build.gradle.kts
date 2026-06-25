plugins {
  id("dd-trace-java.module.internal-platform-component")
}

extra["excludedClassesInstructionCoverage"] = listOf("datadog.context.ContextProviders") // covered by forked test
