package com.datadog.debugger.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class SamplingTest {
  @Test
  public void sampling() {
    Sampling sampling = new Sampling();

    assertEquals(0, sampling.getEventsPerSecond());
    assertEquals(0, sampling.getCoolDownInSeconds());
    assertFalse(sampling.inCoolDown());
  }

  @Test
  public void inCoolDown() {
    Sampling sampling = new Sampling(5);

    assertEquals(0, sampling.getEventsPerSecond());
    assertEquals(5, sampling.getCoolDownInSeconds());
    assertFalse(sampling.inCoolDown());
    assertTrue(sampling.inCoolDown());
  }

  @Test
  public void constructors() {
    assertEquals(new Sampling(0, 0.0), new Sampling());
    assertEquals(new Sampling(0, 42.0), new Sampling(42.0));
    assertEquals(new Sampling(10, 0.0), new Sampling(10));
  }
}
