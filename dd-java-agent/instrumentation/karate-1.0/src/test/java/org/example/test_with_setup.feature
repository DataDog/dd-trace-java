Feature: test with setup

  @setup
  Scenario: setup scenario
    * call read('@setupStep')
    * def data =
    """
    [
     {foo: "bar"}
    ]
    """

  @withSetup
  Scenario Outline: first scenario
    * print foo
    Examples:
      | karate.setup().data |

  @withSetupOnce
  Scenario Outline: second scenario
    * print foo
    Examples:
      | karate.setupOnce().data |

  @ignore @setupStep
  Scenario: setup step
    * print 'test'
