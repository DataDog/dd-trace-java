package com.datadog.profiling.leakmonitor;

@FunctionalInterface
public interface Analyzer {
  double analyze(long timestamp, long used, long committed, long max);
}
