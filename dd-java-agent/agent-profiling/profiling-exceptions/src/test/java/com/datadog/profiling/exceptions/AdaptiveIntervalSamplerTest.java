package com.datadog.profiling.exceptions;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

// disabled unless a system property is specified due to being timing sensitive and unstable in CI
//@EnabledIfSystemProperty(named = "enable.timeSensitive", matches = "true")
public class AdaptiveIntervalSamplerTest {
  @ParameterizedTest
  @MethodSource("samplerParams")
  public void sampleConcurrent(final int threadCnt, final int minInterval, final long timeWindowMs, final long maxSamples)
    throws Exception {
    final AdaptiveIntervalSampler instance =
      new AdaptiveIntervalSampler("test", minInterval, timeWindowMs, maxSamples);

    final AtomicLong allCnt = new AtomicLong(0);
    final long latency = 1_000L;
    final Thread[] threads = new Thread[threadCnt];
    final long hits = (timeWindowMs * (1_000_000L / latency));
    for (int j = 0; j < threads.length; j++) {
      threads[j] =
        new Thread(
          () -> {
            final Random rnd = new Random();
            int cnt = 0;
            for (long i = 0; i < hits / threads.length; i++) {
              cnt += instance.sample() ? 1 : 0;
              LockSupport.parkNanos(rnd.nextInt((int) latency) + 100);
            }

            allCnt.addAndGet(cnt);
          });
      threads[j].start();
    }
    for (int j = 0; j < threads.length; j++) {
      threads[j].join();
    }
    final long cnt = allCnt.get();
    final long diff = cnt - maxSamples;
    System.out.println("threads: " + threadCnt + ", max samples: " + maxSamples + ", samples: " + cnt);
    // diff not larger than 10%
    Assertions.assertTrue(
      diff <= maxSamples * 0.1, "[" + threadCnt + "]: " + cnt + " <= " + maxSamples);
  }

  private static Stream<Arguments> samplerParams() {
    return Stream.of(
      Arguments.of(1, 1, 100L, 500L),
      Arguments.of(1, 1, 100L, 2000L),
      Arguments.of(1, 10, 100L, 500L),
      Arguments.of(1, 10, 100L, 2000L),
      Arguments.of(1, 100, 100L, 500L),
      Arguments.of(1, 100, 100L, 2000L),
      Arguments.of(2, 1, 100L, 500L),
      Arguments.of(2, 1, 100L, 2000L),
      Arguments.of(2, 10, 100L, 500L),
      Arguments.of(2, 10, 100L, 2000L),
      Arguments.of(2, 100, 100L, 500L),
      Arguments.of(2, 100, 100L, 2000L),
      Arguments.of(4, 1, 100L, 500L),
      Arguments.of(4, 1, 100L, 2000L),
      Arguments.of(4, 10, 100L, 500L),
      Arguments.of(4, 10, 100L, 2000L),
      Arguments.of(4, 100, 100L, 500L),
      Arguments.of(4, 100, 100L, 2000L),
      Arguments.of(16, 1, 100L, 500L),
      Arguments.of(16, 1, 100L, 2000L),
      Arguments.of(16, 10, 100L, 500L),
      Arguments.of(16, 10, 100L, 2000L),
      Arguments.of(16, 100, 100L, 500L),
      Arguments.of(16, 100, 100L, 2000L),
      Arguments.of(128, 1, 100L, 500L),
      Arguments.of(128, 1, 100L, 2000L),
      Arguments.of(128, 10, 100L, 500L),
      Arguments.of(128, 10, 100L, 2000L),
      Arguments.of(128, 100, 100L, 500L),
      Arguments.of(128, 100, 100L, 2000L));
  }
}
