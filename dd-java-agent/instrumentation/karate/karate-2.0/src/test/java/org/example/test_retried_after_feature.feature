Feature: test retried after feature

  Scenario: flaky scenario
    * Java.type('org.example.RetriedAfterFeatureFlaky').flake()
