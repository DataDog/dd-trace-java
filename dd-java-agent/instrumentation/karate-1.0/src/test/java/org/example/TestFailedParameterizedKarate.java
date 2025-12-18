package org.example;

import com.intuit.karate.junit5.Karate;

public class TestFailedParameterizedKarate {

  @Karate.Test
  public Karate testSucceed() {
    return Karate.run("classpath:org/example/test_failed_parameterized.feature");
  }
}
