package datadog.trace.api.civisibility.execution;

import static datadog.trace.api.civisibility.execution.ExecutionAggregation.MIXED;
import static datadog.trace.api.civisibility.execution.ExecutionAggregation.NONE;
import static datadog.trace.api.civisibility.execution.ExecutionAggregation.ONLY_FAILED;
import static datadog.trace.api.civisibility.execution.ExecutionAggregation.ONLY_PASSED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExecutionAggregationTest {

  @Test
  void noneAfterFailIsOnlyFailed() {
    assertEquals(ONLY_FAILED, NONE.withExecution(TestStatus.fail));
  }

  @Test
  void noneAfterPassIsOnlyPassed() {
    assertEquals(ONLY_PASSED, NONE.withExecution(TestStatus.pass));
  }

  @Test
  void noneAfterSkipIsOnlyPassed() {
    assertEquals(ONLY_PASSED, NONE.withExecution(TestStatus.skip));
  }

  @Test
  void onlyFailedAfterFailStaysOnlyFailed() {
    assertEquals(ONLY_FAILED, ONLY_FAILED.withExecution(TestStatus.fail));
  }

  @Test
  void onlyFailedAfterPassIsMixed() {
    assertEquals(MIXED, ONLY_FAILED.withExecution(TestStatus.pass));
  }

  @Test
  void onlyPassedAfterPassStaysOnlyPassed() {
    assertEquals(ONLY_PASSED, ONLY_PASSED.withExecution(TestStatus.pass));
  }

  @Test
  void onlyPassedAfterFailIsMixed() {
    assertEquals(MIXED, ONLY_PASSED.withExecution(TestStatus.fail));
  }

  @Test
  void mixedAfterAnyStatusStaysMixed() {
    assertEquals(MIXED, MIXED.withExecution(TestStatus.pass));
    assertEquals(MIXED, MIXED.withExecution(TestStatus.fail));
    assertEquals(MIXED, MIXED.withExecution(TestStatus.skip));
  }
}
