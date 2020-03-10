package datadog.trace.bootstrap.jfr.instrumentation.exceptions;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ExceptionHistogramTest {
  private static final int MAX_ITEMS = 2;
  private ExceptionHistogram instance;

  @BeforeEach
  public void setup() throws Exception {
    instance = new ExceptionHistogram(MAX_ITEMS, true);
  }

  @Test
  public void recordTypeSanity() {
    instance.record((Exception) null);
    instance.record(new NullPointerException());
  }

  @Test
  public void recordNameSanity() {
    instance.record((String) null);
    instance.record(NullPointerException.class.getName());
  }

  @Test
  public void recordAndProcess() {
    String[] exceptionNames =
        new String[] {
          NullPointerException.class.getName(),
          IllegalArgumentException.class.getName(),
          OutOfMemoryError.class.getName()
        };
    long[] exceptionCounts = new long[] {8, 5, 1};

    for (int i = 0; i < exceptionNames.length; i++) {
      for (int j = 0; j < exceptionCounts[i]; j++) {
        instance.record(exceptionNames[i]);
      }
    }

    String[] histoNames = new String[exceptionNames.length];
    long[] histoCounts = new long[exceptionCounts.length];
    AtomicInteger processCounter = new AtomicInteger();

    instance.processAndReset(
        (k, v) -> {
          int cntr = processCounter.getAndIncrement();
          histoNames[cntr] = k;
          histoCounts[cntr] = v;
        });

    int count = processCounter.get();
    Assert.assertEquals(MAX_ITEMS, count);
    Assertions.assertArrayEquals(
        Arrays.copyOf(exceptionNames, count), Arrays.copyOf(histoNames, count));
    Assertions.assertArrayEquals(
        Arrays.copyOf(exceptionCounts, count), Arrays.copyOf(histoCounts, count));

    // make sure that the previous call properly reset the histo
    processCounter.set(0);
    instance.processAndReset((k, v) -> processCounter.getAndIncrement());
    Assert.assertEquals(0, processCounter.get());
  }
}
