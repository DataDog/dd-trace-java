@foo
Feature: Basic Arithmetic With Examples

  Background: A Calculator
    Given a calculator I just turned on

  Scenario Outline: Many additions
    Given the previous entries:
      | first | second | operation |
      | 1     | 1      | +         |
      | 2     | 1      | +         |
    When I press +
    And I add <a> and <b>
    And I press +
    Then the result is <c>

    Examples: Single digits
      | a | b | c  |
      | 1 | 2 | 12  |
      | 2 | 3 | 23 |

