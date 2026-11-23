package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.time.ControllableTimeSource;
import org.junit.jupiter.api.Test;

class ControllableTimeSourceTest {

  @Test
  void timeSourceChanges() {
    ControllableTimeSource timeSource = new ControllableTimeSource();

    timeSource.set(1000);
    assertEquals(1000, timeSource.getNanoTicks());

    timeSource.advance(500);
    assertEquals(1500, timeSource.getNanoTicks());

    timeSource.advance(-800);
    assertEquals(700, timeSource.getNanoTicks());
  }
}
