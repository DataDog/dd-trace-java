package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.intuit.karate.Results;
import com.intuit.karate.Runner;
import org.junit.jupiter.api.Test;

public class TestSucceedParallelKarate {

  @Test
  public void testSucceed() {
    Results results = Runner.path("classpath:org/example/test_succeed.feature").parallel(4);
    assertEquals(0, results.getFailCount(), results.getErrorMessages());
  }
}
