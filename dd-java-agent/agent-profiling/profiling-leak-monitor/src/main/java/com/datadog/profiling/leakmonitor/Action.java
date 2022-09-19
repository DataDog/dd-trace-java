package com.datadog.profiling.leakmonitor;

public interface Action {
  void apply();

  void revert();
}
