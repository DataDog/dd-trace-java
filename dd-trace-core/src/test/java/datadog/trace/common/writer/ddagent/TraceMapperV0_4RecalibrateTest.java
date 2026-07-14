package datadog.trace.common.writer.ddagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TraceMapperV0_4RecalibrateTest {

  @Test
  void recalibratesOncePerInterval() {
    final long interval = TraceMapperV0_4.RECALIBRATE_SPAN_INTERVAL;

    // The span counter starts at 1 (incrementAndGet), so no recalibrate until the first boundary.
    assertFalse(TraceMapperV0_4.shouldRecalibrate(1));
    assertFalse(TraceMapperV0_4.shouldRecalibrate(interval - 1));
    assertTrue(TraceMapperV0_4.shouldRecalibrate(interval));
    assertFalse(TraceMapperV0_4.shouldRecalibrate(interval + 1));
    assertTrue(TraceMapperV0_4.shouldRecalibrate(2 * interval));

    // Exactly one recalibrate per `interval` spans across a full sweep.
    int recalibrations = 0;
    for (long count = 1; count <= 4 * interval; count++) {
      if (TraceMapperV0_4.shouldRecalibrate(count)) {
        recalibrations++;
      }
    }
    assertEquals(4, recalibrations);
  }

  @Test
  void intervalIsAPowerOfTwo() {
    // shouldRecalibrate uses a bit mask, which is only correct for a power-of-two interval.
    final long interval = TraceMapperV0_4.RECALIBRATE_SPAN_INTERVAL;
    assertEquals(0, interval & (interval - 1), "RECALIBRATE_SPAN_INTERVAL must be a power of two");
  }
}
