@foo
Feature: Basic Arithmetic

  Background: A Calculator
    Given a calculator I just turned on

  Scenario: Addition
    When I slowly add 4 and 5
    Then the result is 9
