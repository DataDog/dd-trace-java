Feature: Basic Arithmetic

  Background: A Calculator
    Given a calculator I just turned on

  @datadog_efd_disable
  Scenario: Addition
    When I add 4 and 5
    Then the result is 9
