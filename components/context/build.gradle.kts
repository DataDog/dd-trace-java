apply(from = "$rootDir/gradle/java.gradle")

extra["excludedClassesInstructionCoverage"] =
  listOf("datadog.context.ContextProviders") // covered by forked test

// excluded from the default 90% rule so the relaxed 80% rule below can apply instead
// (couple of branches involve a nanosecond CAS race that can't be reliably reproduced)
extra["excludedClassesBranchCoverage"] =
  listOf("datadog.context.ThreadLocalContextManager.ContextContinuationImpl")

tasks.named<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>("jacocoTestCoverageVerification") {
  violationRules {
    rule {
      element = "CLASS"
      includes = listOf("datadog.context.ThreadLocalContextManager.ContextContinuationImpl")
      limit {
        counter = "BRANCH"
        minimum = "0.8".toBigDecimal()
      }
    }
  }
}
