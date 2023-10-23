Feature: test parameterized

  Scenario Outline: first scenario as an outline
  (to prevent a particular bug from re-appearing)

    Given def p = <param>
    When def response = p + p
    Then match response == value

    Examples:
      | param | value |
      | 'a'   | aa    |
      | 'b'   | bb    |
