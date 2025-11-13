plugins {
  java
}

logger.info("Avoid executing classes used to test testing frameworks instrumentation")

tasks.withType<Test>().configureEach {
  exclude("**/TestAssumption*", "**/TestSuiteSetUpAssumption*")
  exclude("**/TestDisableTestTrace*")
  exclude("**/TestError*")
  exclude("**/TestFactory*")
  exclude("**/TestFailed*")
  exclude("**/TestFailedWithSuccessPercentage*")
  exclude("**/TestInheritance*", "**/BaseTestInheritance*")
  exclude("**/TestParameterized*")
  exclude("**/TestRepeated*")
  exclude("**/TestSkipped*")
  exclude("**/TestSkippedClass*")
  exclude("**/TestSucceed*")
  exclude("**/TestTemplate*")
  exclude("**/TestUnskippable*")
  exclude("**/TestWithSetup*")
}
