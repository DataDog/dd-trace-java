package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.intuit.karate.Results;
import com.intuit.karate.Runner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

@DisabledForJreRange(
    min = JRE.JAVA_24,
    disabledReason =
        "Karate does not support Java 24+ yet: https://github.com/karatelabs/karate/blob/master/.github/workflows/jdk-compat.yml#L18")
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
