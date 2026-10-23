package com.datadog.profiling.ddprof;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TraceContextGateTest {

  @Test
  void alwaysOpenWhenNotArmed() {
    TraceContextGate gate = new TraceContextGate(false);
    assertTrue(gate.isOpen());

    gate.onRecordingStarted();
    assertTrue(gate.isOpen());

    gate.onRecordingStopped();
    assertTrue(gate.isOpen());
  }

  @Test
  void openOnlyWhileRecordingWhenArmed() {
    TraceContextGate gate = new TraceContextGate(true);
    assertFalse(gate.isOpen());

    gate.onRecordingStarted();
    assertTrue(gate.isOpen());

    gate.onRecordingStopped();
    assertFalse(gate.isOpen());

    gate.onRecordingStarted();
    assertTrue(gate.isOpen());
  }

  @Test
  void notArmedByDefault() {
    // the test JVM doesn't set profiling.enabled=armed
    assertTrue(TraceContextGate.INSTANCE.isOpen());
  }
}
