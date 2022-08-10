package com.datadog.profiling.context;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntervalEncoderTest {
  private Instant now;
  private long nowNanos;
  private IntervalEncoder instance;

  @BeforeEach
  void setup() {
    now = Instant.now();
    nowNanos = now.toEpochMilli() * 1_000_000L + now.getNano();
    instance = new IntervalEncoder(nowNanos, 1000, 2, 32);
  }

  @Test
  void testFinishUnstarted() {
    assertNotNull(instance.finish());
  }

  @Test
  void testFinishThreadInFlight() {
    IntervalEncoder.ThreadEncoder encoder = instance.startThread(1);
    assertThrows(IllegalStateException.class, instance::finish);
  }

  @Test
  void testDoubleFinish() {
    instance.finish();
    assertThrows(IllegalStateException.class, instance::finish);
  }

  @Test
  void testStartTooManyThreads() {
    instance.startThread(1).finish();
    instance.startThread(2).finish();
    assertThrows(IllegalStateException.class, () -> instance.startThread(3));
  }

  @Test
  void testStartMultiThreads() {
    instance.startThread(1);
    assertThrows(IllegalStateException.class, () -> instance.startThread(2));

    IntervalEncoder instance1 = new IntervalEncoder(nowNanos, 1000, 1, 100);
    instance1.startThread(1);
    assertThrows(IllegalStateException.class, () -> instance1.startThread(2));
  }

  @Test
  void testEncodeSequence() {
    IntervalEncoder.ThreadEncoder encoder = instance.startThread(1);
    encoder.recordInterval(200, 300);
    encoder.recordInterval(500, 600);
    ByteBuffer data = encoder.finish().finish();
    assertNotNull(data);
    assertTrue(data.limit() > 0);

    List<IntervalParser.Interval> intervals = new IntervalParser().parseIntervals(data.array());
    IntervalParser.Interval i1 = intervals.get(0);
    assertEquals(nowNanos + 200, i1.from);
  }

  @Test
  void testEncodeSequenceTruncated() {
    IntervalEncoder.ThreadEncoder encoder = instance.startThread(1);
    encoder.recordInterval(200, 300);
    encoder.recordInterval(500, 600);
    ByteBuffer data = encoder.finish().finish();
    assertNotNull(data);
    assertTrue(data.limit() > 0);

    List<IntervalParser.Interval> intervals = new IntervalParser().parseIntervals(data.array());
    IntervalParser.Interval i1 = intervals.get(0);
    assertEquals(nowNanos + 200, i1.from);
  }

  @Test
  void testIntervalAfterFinish() {
    IntervalEncoder.ThreadEncoder encoder = instance.startThread(1);
    encoder.recordInterval(200, 300);
    encoder.finish();
    assertThrows(IllegalStateException.class, () -> encoder.recordInterval(500, 600));
  }

  @Test
  void testThreadDoubleFinish() {
    IntervalEncoder.ThreadEncoder encoder = instance.startThread(1);
    encoder.recordInterval(200, 300);
    encoder.finish();
    assertThrows(IllegalStateException.class, encoder::finish);
  }
}
