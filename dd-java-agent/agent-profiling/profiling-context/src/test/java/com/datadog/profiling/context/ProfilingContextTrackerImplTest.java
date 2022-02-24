package com.datadog.profiling.context;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.profiling.context.allocator.Allocators;
import datadog.trace.util.Base64Encoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProfilingContextTrackerImplTest {
  private ProfilingContextTrackerImpl instance;

  @BeforeEach
  void setup() throws Exception {
    instance = new ProfilingContextTrackerImpl(Allocators.heapAllocator(32, 16), 0L);
  }

  @Test
  void activateContext() {
    for (int i = 1; i <= 4; i++) {
      assertTrue(instance.activateContext(1L, i * 1000L));
    }
    assertFalse(instance.activateContext(1L, 10_000L));
  }

  @Test
  void deactivateContext() {}

  @Test
  void persist() {
    instance = new ProfilingContextTrackerImpl(Allocators.heapAllocator(8192, 64), 0L);
    for (int i = 0; i < 40; i += 4) {
      instance.activateContext(1L, (i + 1) * 1_000_000L);
      instance.deactivateContext(1L, (i + 2) * 1_000_000L, false);
      instance.activateContext(2L, (i + 3) * 1_000_000L);
      instance.deactivateContext(2L, (i + 4) * 1_000_000L, true);
    }

    byte[] persisted = instance.persist();
    assertNotNull(persisted);

    List<IntervalParser.Interval> intervals = new IntervalParser().parseIntervals(persisted);
    assertEquals(11, intervals.size());

    byte[] encoded = new Base64Encoder(false).encode(persisted);
    System.out.println("===> encoded: " + encoded.length);
    System.err.println("===> " + new String(encoded, StandardCharsets.UTF_8));
  }
}
