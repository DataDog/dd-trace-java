package com.datadog.profiling.exceptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StreamingSamplerTest {
  private static final class TimestampProvider implements Supplier<Long> {
    private final Random rnd = new Random();
    private long ts = 0;
    private final int step;
    private final double stdDev;

    TimestampProvider(
        final int windowDuration, final int totalWindows, final int hits, final double stdDev) {
      final long totalDuration =
          TimeUnit.NANOSECONDS.convert(windowDuration, TimeUnit.SECONDS) * totalWindows;
      step =
          (int)
              TimeUnit.MILLISECONDS.convert(
                  Math.round(totalDuration / (double) hits), TimeUnit.NANOSECONDS);
      this.stdDev = stdDev;
    }

    @Override
    public Long get() {
      ts += (step + ((rnd.nextGaussian() * step) * stdDev)) * 1_000_000L;
      return ts;
    }
  }

  @ParameterizedTest(name = "{index}")
  @MethodSource("samplerParams")
  void sample(
      final int threadCnt,
      final int windowDuration,
      final int samplesPerWindow,
      final int totalWindows,
      final int hits,
      final double clockStdDev,
      final double errorDev)
      throws Exception {
    final ThreadLocal<TimestampProvider> tsProviderRef =
        ThreadLocal.withInitial(
            () -> new TimestampProvider(windowDuration, totalWindows, hits, clockStdDev));
    final StreamingSampler instance =
        new StreamingSampler(
            windowDuration, TimeUnit.SECONDS, samplesPerWindow, () -> tsProviderRef.get().get());

    final AtomicInteger allCnt = new AtomicInteger(0);
    final Thread[] threads = new Thread[threadCnt];
    for (int j = 0; j < threads.length; j++) {
      threads[j] =
          new Thread(
              () -> {
                int cnt = 0;
                for (long i = 0; i < hits; i++) {
                  cnt += instance.sample() ? 1 : 0;
                }

                allCnt.addAndGet(cnt);
              });
      threads[j].start();
    }
    for (final Thread thread : threads) {
      thread.join();
    }
    final double perWindow = (allCnt.get() / (double) totalWindows);
    System.out.println("===> " + allCnt.get() + ", " + perWindow);
    final double dev =
        perWindow > samplesPerWindow ? (perWindow - samplesPerWindow) / samplesPerWindow : 0;
    Assertions.assertTrue(
        dev <= errorDev,
        allCnt.get() + " <= (" + samplesPerWindow + " * " + totalWindows + ") [" + threadCnt + ']');
  }

  private static Stream<Arguments> samplerParams() {
    final List<Arguments> args = new ArrayList<>();
    for (int threadCnt = 1; threadCnt < 128; threadCnt *= 2) {
      for (int windows = 1; windows < 20; windows += 2) {
        args.add(Arguments.of(threadCnt, 10, 5, windows, 511, 0.001d, 0.1d));
        args.add(Arguments.of(threadCnt, 10, 5, windows, 511, 0.5d, 1d));
        args.add(Arguments.of(threadCnt, 10, 5, windows, 511, 1d, 1d));
      }
    }
    return args.stream();
  }
}
