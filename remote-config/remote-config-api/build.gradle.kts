plugins {
  id("dd-trace-java.module.internal-api")
}

extra["excludedClassesBranchCoverage"] = listOf("datadog.remoteconfig.ConfigurationChangesListener.PollingHinterNoop")
