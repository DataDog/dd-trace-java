package datadog.trace.common.writer.ddagent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TraceMapperV0_4RecalibrateTest {

  @Test
  void recalibratesOncePerInterval() {
    final long interval = TraceMapperV0_4.RECALIBRATE_SPAN_INTERVAL;

    // shouldRecalibrate() advances a shared counter, so assert a property that holds regardless of
    // the starting value: any window of 2*interval consecutive spans crosses exactly two interval
    // boundaries.
    int recalibrations = 0;
    for (long i = 0; i < 2 * interval; i++) {
      if (TraceMapperV0_4.shouldRecalibrate()) {
        recalibrations++;
      }
    }
    assertEquals(2, recalibrations);
  }

  @Test
  void intervalIsAPowerOfTwo() {
    // shouldRecalibrate uses a bit mask, which is only correct for a power-of-two interval.
    final long interval = TraceMapperV0_4.RECALIBRATE_SPAN_INTERVAL;
    assertEquals(0, interval & (interval - 1), "RECALIBRATE_SPAN_INTERVAL must be a power of two");
  }
}
