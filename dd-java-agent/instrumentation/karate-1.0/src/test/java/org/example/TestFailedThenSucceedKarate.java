package org.example;

import com.intuit.karate.junit5.Karate;

public class TestFailedThenSucceedKarate {

  @Karate.Test
  public Karate testFailed() {
    return Karate.run("classpath:org/example/test_failed_then_succeed.feature");
  }
}
