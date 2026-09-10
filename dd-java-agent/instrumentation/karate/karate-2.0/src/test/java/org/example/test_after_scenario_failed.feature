Feature: test after scenario failed

  Scenario: after scenario failed
    * configure afterScenario = function() { karate.fail('after scenario failed') }
    * def value = true
