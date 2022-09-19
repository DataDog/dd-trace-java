package com.datadog.profiling.leakmonitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MovingAverageTrendAnalyzerTest {

  public static Stream<Arguments> lookbacks() {
    return Stream.of(
        Arguments.of(10, 5),
        Arguments.of(10, 3),
        Arguments.of(100, 3),
        Arguments.of(50, 20),
        Arguments.of(16, 8));
  }

  @ParameterizedTest
  @MethodSource("lookbacks")
  public void testIdentifyTrendChanges(int longTermLookBack, int shortTermLookBack) {
    MovingAverageUsedHeapTrendAnalyzer analyzer =
        new MovingAverageUsedHeapTrendAnalyzer(longTermLookBack, shortTermLookBack);
    // no trend reported until enough historical data is collected
    int i = 0;
    for (; i < longTermLookBack - 1; i++) {
      assertEquals(analyzer.analyze(0, i, 0, 0), 0);
    }
    // the value is increasing so report a ~ +1 signal,
    // go to a big number to check the ringbuffer doesn't overflow
    double last = 0D;
    for (; i < 10_000; i++) {
      double signal = analyzer.analyze(0, i, 0, 0);
      assertEquals(1, Math.ceil(signal));
      assertTrue(signal >= last);
      last = signal;
    }
    i--;
    // the value has peaked and is trending down,
    // the signal won't change sign until memory of the uptrend has passed
    while (last > 0) {
      double signal = analyzer.analyze(0, i--, 0, 0);
      assertTrue(signal <= last);
      last = signal;
    }
    // TODO calculate how long the flipover should take (without reimplementing the SUT)
    assertTrue(i >= 10_000 - longTermLookBack);
    // it's going down now and there's no memory of an uptrend
    // - produce increasingly negative signals
    for (; i >= 0; i--) {
      double signal = analyzer.analyze(0, i, 0, 0);
      assertEquals(-1, Math.floor(signal));
      assertTrue(signal <= last);
      last = signal;
    }
  }
}
