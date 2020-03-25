package com.datadog.profiling.exceptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StreamingSamplerTest {
  private static final class TimestampProvider implements Supplier<Long> {
    private final Random rnd = new Random();
    private final AtomicLong ts = new AtomicLong(0L);
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
      final double diff =
          Math.max(
              (step + ((rnd.nextGaussian() * step) * stdDev)) * 1_000_000L,
              1); // at least 1ns progress
      return ts.getAndAdd(Math.round(diff));
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
      final double clockStdDev)
      throws Exception {

    final AtomicInteger windowCounter = new AtomicInteger(1); // implicitly 1 window
    final TimestampProvider tsProvider =
        new TimestampProvider(windowDuration, totalWindows, hits, clockStdDev);
    final StreamingSampler instance =
        new StreamingSampler(windowDuration, TimeUnit.SECONDS, samplesPerWindow, 10, tsProvider) {
          @Override
          protected void onWindowRoll(final SamplerState state) {
            windowCounter.incrementAndGet();
          }
        };

    final AtomicInteger allCnt = new AtomicInteger(0);
    final Thread[] threads = new Thread[threadCnt];
    System.out.println(
        "==> windows: "
            + totalWindows
            + ", threads: "
            + threadCnt
            + ", clockDev: "
            + clockStdDev
            + ", samplesPerWindow: "
            + samplesPerWindow);
    for (int j = 0; j < threads.length; j++) {
      threads[j] =
          new Thread(
              () -> {
                int cnt = 0;
                for (long i = 0; i < hits; i++) {
                  if (instance.sample()) {
                    cnt += 1;
                  }
                }
                allCnt.addAndGet(cnt);
              });
      threads[j].start();
    }
    for (final Thread thread : threads) {
      thread.join();
    }
    final double perWindow = (allCnt.get() / (double) windowCounter.get());
    System.out.println("===> " + allCnt.get() + ", " + perWindow + ", " + windowCounter.get());
    System.out.println();
    final double dev = perWindow - samplesPerWindow;
    Assertions.assertTrue(
        dev <= 0.2d * samplesPerWindow,
        allCnt.get() + " <= (" + samplesPerWindow + " * " + totalWindows + ") [" + threadCnt + ']');
  }

  private static Stream<Arguments> samplerParams() {
    final List<Arguments> args = new ArrayList<>();
    for (int threadCnt = 1; threadCnt < 64; threadCnt *= 2) {
      for (int windows = 1; windows <= 16; windows *= 2) {
        for (int samples = 5; samples <= 40; samples *= 2) {
          args.add(Arguments.of(threadCnt, 10, samples * 10, windows, 511, 0.001d));
          args.add(Arguments.of(threadCnt, 10, samples, windows, 511, 0.5d));
          args.add(Arguments.of(threadCnt, 10, samples, windows, 511, 1d));
        }
      }
    }
    return args.stream();
  }
}
