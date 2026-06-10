plugins {
  id("dd-trace-java.module.internal-component")
}

extra["excludedClassesInstructionCoverage"] = listOf("datadog.context.ContextProviders") // covered by forked test
