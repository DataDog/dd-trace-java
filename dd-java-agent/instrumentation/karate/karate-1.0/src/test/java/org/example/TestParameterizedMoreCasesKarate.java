package org.example;

import com.intuit.karate.junit5.Karate;

public class TestParameterizedMoreCasesKarate {

  @Karate.Test
  public Karate testSucceed() {
    return Karate.run("classpath:org/example/test_parameterized_more_cases.feature");
  }
}
