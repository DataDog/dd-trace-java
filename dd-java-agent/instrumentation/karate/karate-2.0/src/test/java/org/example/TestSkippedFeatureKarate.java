package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.intuit.karate.Results;
import com.intuit.karate.Runner;
import org.junit.jupiter.api.Test;

public class TestSkippedFeatureKarate {

  @Test
  void testParallel() {
    Results results =
        Runner.path("classpath:org/example/test_succeed.feature")
            .systemProperty("karate.options", "--tags ~@foo")
            .parallel(1);
    assertEquals(0, results.getFailCount(), results.getErrorMessages());
  }
}
