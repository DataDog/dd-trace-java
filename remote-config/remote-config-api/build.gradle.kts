apply(from = "$rootDir/gradle/java.gradle")

extra["excludedClassesBranchCoverage"] = listOf("datadog.remoteconfig.ConfigurationChangesListener.PollingHinterNoop")
