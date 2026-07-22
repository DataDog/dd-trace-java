@foo
Feature: test succeed

  @bar
  Scenario: first scenario
    * Java.type('org.example.Slow').stall()
