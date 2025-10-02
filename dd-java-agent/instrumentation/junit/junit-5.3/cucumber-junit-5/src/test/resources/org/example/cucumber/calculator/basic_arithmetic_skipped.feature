@foo
Feature: Basic Arithmetic

  Background: A Calculator
    Given a calculator I just turned on

  @Disabled
  Scenario: Addition
    When I add 4 and 5
    Then the result is 9

  Scenario: Subtraction
    When I add 4 and -5
    Then the result is -1
