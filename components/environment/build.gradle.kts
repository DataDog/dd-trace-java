plugins {
  `java-library`
}

apply(from = "$rootDir/gradle/java.gradle")

extra.set("minimumInstructionCoverage", 0.7)
val excludedClassesCoverage by extra {
  listOf(
    "datadog.environment.JavaVirtualMachine", // depends on OS and JVM vendor
    "datadog.environment.JavaVirtualMachine.JvmOptionsHolder", // depends on OS and JVM vendor
    "datadog.environment.JvmOptions", // depends on OS and JVM vendor
    "datadog.environment.OperatingSystem", // depends on OS
  )
}
val excludedClassesBranchCoverage by extra {
  listOf("datadog.environment.CommandLine") // tested using forked process
}
