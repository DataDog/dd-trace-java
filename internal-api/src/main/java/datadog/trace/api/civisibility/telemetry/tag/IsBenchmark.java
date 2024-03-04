package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/**
 * Whether a test case is a benchmark (that is a test cases of a benchmarking framework, e.g. JMH).
 */
public enum IsBenchmark implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "is_benchmark:true";
  }
}
