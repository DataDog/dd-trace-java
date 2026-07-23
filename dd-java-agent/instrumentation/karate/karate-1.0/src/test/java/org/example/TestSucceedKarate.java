package org.example;

import com.intuit.karate.junit5.Karate;

public class TestSucceedKarate {

  @Karate.Test
  public Karate testSucceed() {
    return Karate.run("classpath:org/example/test_succeed.feature");
  }
}
