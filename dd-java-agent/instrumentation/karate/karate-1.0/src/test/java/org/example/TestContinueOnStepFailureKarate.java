package org.example;

import com.intuit.karate.junit5.Karate;

public class TestContinueOnStepFailureKarate {

  @Karate.Test
  public Karate test() {
    return Karate.run("classpath:org/example/test_continue_on_step_failure.feature");
  }
}
