Feature: test with setup

  @setup
  Scenario: setup scenario
    * def data =
    """
    [
     {foo: "bar"}
    ]
    """

  Scenario Outline: first scenario
    * print foo
    Examples:
      | karate.setup().data |

  Scenario Outline: second scenario
    * print foo
    Examples:
      | karate.setupOnce().data |
