package com.datadog.profiling.leakmonitor;

public enum Analyzers {
  MOVING_AVERAGE_CROSSOVER {
    @Override
    public Analyzer create() {
      return new MovingAverageUsedHeapTrendAnalyzer(5, 2);
    }
  };

  public abstract Analyzer create();
}
