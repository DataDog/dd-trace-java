package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.intuit.karate.Results;
import com.intuit.karate.Runner;
import org.junit.jupiter.api.Test;

public class TestSucceedKarateSlow {

  @Test
  public void test() {
    Results results = Runner.path("classpath:org/example/test_succeed_slow.feature").parallel(1);
    assertEquals(0, results.getFailCount(), results.getErrorMessages());
  }
}
