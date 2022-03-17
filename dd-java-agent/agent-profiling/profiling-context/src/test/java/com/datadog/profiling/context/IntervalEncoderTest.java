package com.datadog.profiling.context;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntervalEncoderTest {
  private IntervalEncoder instance;

  @BeforeEach
  void setup() {
    instance = new IntervalEncoder(1L, 1000, 2, 32);
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

    IntervalEncoder instance1 = new IntervalEncoder(1, 1000, 1, 100);
    instance1.startThread(1);
    assertThrows(IllegalStateException.class, () -> instance1.startThread(2));
  }

  @Test
  void testEncodeSequence() {
    IntervalEncoder.ThreadEncoder encoder = instance.startThread(1);
    encoder.recordInterval(100, 200);
    encoder.recordInterval(500, 600);
    ByteBuffer data = encoder.finish().finish();
    assertNotNull(data);
    assertTrue(data.limit() > 0);
  }

  @Test
  void testIntervalAfterFinish() {
    IntervalEncoder.ThreadEncoder encoder = instance.startThread(1);
    encoder.recordInterval(100, 200);
    encoder.finish();
    assertThrows(IllegalStateException.class, () -> encoder.recordInterval(500, 600));
  }

  @Test
  void testThreadDoubleFinish() {
    IntervalEncoder.ThreadEncoder encoder = instance.startThread(1);
    encoder.recordInterval(100, 200);
    encoder.finish();
    assertThrows(IllegalStateException.class, encoder::finish);
  }
}
