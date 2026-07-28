Feature: test abort suite

  @fail
  Scenario: aborting scenario
    * configure abortSuiteOnFailure = true
    * match 1 == 1
