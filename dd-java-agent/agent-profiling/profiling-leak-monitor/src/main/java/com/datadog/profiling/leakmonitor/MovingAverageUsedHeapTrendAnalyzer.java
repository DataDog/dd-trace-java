package com.datadog.profiling.leakmonitor;

/**
 * Tracks the heap used (ignores timestamps, committed, and max) and produces a signal in the range
 * [-1, 1], where 1 indicates a strong increasing trend, and -1 a strong decreasing trend.
 *
 * <p>The implementation uses a ring buffer and two moving averages over different lookbacks. The
 * signal changes direction whenever the "fast average" over the shorter lookback crosses the "slow
 * average" over the longer lookback. During a trend change, the trend increases/decreases
 * gradually, and the reaction speed can be tuned by adjusting the lookback periods.
 */
public class MovingAverageUsedHeapTrendAnalyzer implements Analyzer {
  private final double[] values;
  private final int longTermLookBack;
  private final int shortTermLookBack;
  private final int mask;
  private double fastSum = 0D;
  private double slowSum = 0D;
  private int index;

  public MovingAverageUsedHeapTrendAnalyzer(int longTermLookBack, int shortTermLookBack) {
    this.values = new double[nextPowerOf2(longTermLookBack)];
    this.mask = values.length - 1;
    this.longTermLookBack = longTermLookBack;
    this.shortTermLookBack = shortTermLookBack;
    this.index = -1;
  }

  public double analyze(long timestamp, long used, long committed, long max) {
    ++index;
    slowSum += used - values[((index - longTermLookBack) & mask)];
    fastSum += used - values[((index - shortTermLookBack) & mask)];
    values[(index & mask)] = used;
    if (index >= longTermLookBack - 1) {
      double fastAvg = fastSum / shortTermLookBack;
      double slowAvg = slowSum / longTermLookBack;
      return Math.tanh(fastAvg - slowAvg);
    }
    return 0D;
  }

  double shortTermAvg() {
    return fastSum / shortTermLookBack;
  }

  private static int nextPowerOf2(int size) {
    return 1 << -Long.numberOfLeadingZeros(size - 1);
  }
}
