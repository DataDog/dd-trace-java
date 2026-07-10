Feature: test continue on step failure

  Scenario: flaky scenario
    * configure continueOnStepFailure = { enabled: true, continueAfter: true }
    * def pass = Java.type('org.example.Flaky').shouldPass()
    * match pass == true
    * match pass == true
