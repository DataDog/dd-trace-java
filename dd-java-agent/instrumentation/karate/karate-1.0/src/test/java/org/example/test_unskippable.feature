@foo
Feature: test unskippable

  @bar
  @datadog_itr_unskippable
  Scenario: first scenario
    * print 'first'

  Scenario: second scenario
    * print 'second'
