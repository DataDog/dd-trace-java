plugins {
  id("dd-trace-java.module.internal-component")
}

extra["excludedClassesBranchCoverage"] = listOf("datadog.remoteconfig.ConfigurationChangesListener.PollingHinterNoop")
