Feature: vetoed steps

  Scenario: veto a middle and final step
    * def first = true
    * def vetoMiddle = true
    * match first == true
    * def vetoFinal = true
