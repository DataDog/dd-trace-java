apply(from = "$rootDir/gradle/java.gradle")

val excludedClassesBranchCoverage by extra(
  listOf(
    "datadog.remoteconfig.ConfigurationChangesListener.PollingHinterNoop",
  ),
)
