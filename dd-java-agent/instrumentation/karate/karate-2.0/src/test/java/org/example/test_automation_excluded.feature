Feature: automation exclusions

  @datadog_no_auto_retry @datadog_prevent_failure_suppression
  Scenario: protected_failure
    * assert false
