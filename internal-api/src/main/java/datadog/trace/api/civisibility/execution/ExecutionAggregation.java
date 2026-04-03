package datadog.trace.api.civisibility.execution;

/** Tracks the aggregate result of test executions. */
public enum ExecutionAggregation {
  NONE,
  ONLY_FAILED,
  ONLY_PASSED,
  MIXED;

  /** Returns the new state after registering an execution with the given status. */
  public ExecutionAggregation withExecution(TestStatus status) {
    boolean failed = (status == TestStatus.fail);
    switch (this) {
      case NONE:
        return failed ? ONLY_FAILED : ONLY_PASSED;
      case ONLY_FAILED:
        return failed ? ONLY_FAILED : MIXED;
      case ONLY_PASSED:
        return failed ? MIXED : ONLY_PASSED;
      case MIXED:
        return MIXED;
      default:
        throw new IllegalStateException("Unknown state: " + this);
    }
  }
}
