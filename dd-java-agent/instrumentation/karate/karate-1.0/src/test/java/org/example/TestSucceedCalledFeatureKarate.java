package org.example;

import com.intuit.karate.junit5.Karate;

public class TestSucceedCalledFeatureKarate {

  @Karate.Test
  public Karate testCalledFeature() {
    return Karate.run("classpath:org/example/test_called_feature.feature");
  }
}
