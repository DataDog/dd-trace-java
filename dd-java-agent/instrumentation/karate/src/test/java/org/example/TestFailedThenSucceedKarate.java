package org.example;

import com.intuit.karate.junit5.Karate;
import org.junit.jupiter.api.condition.DisabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

@DisabledForJreRange(
    min = JRE.JAVA_24,
    disabledReason =
        "Karate does not support Java 24+ yet: https://github.com/karatelabs/karate/blob/master/.github/workflows/jdk-compat.yml#L18")
public class TestFailedThenSucceedKarate {

  @Karate.Test
  public Karate testFailed() {
    return Karate.run("classpath:org/example/test_failed_then_succeed.feature");
  }
}
