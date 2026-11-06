package org.example;

import com.intuit.karate.junit5.Karate;

public class TestAutomationExcludedKarate {

  @Karate.Test
  public Karate testFailed() {
    return Karate.run("classpath:org/example/test_automation_excluded.feature");
  }
}
