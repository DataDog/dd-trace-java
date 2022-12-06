package datadog.trace.bootstrap.debugger;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CorrelationAccessTest {
  @Test
  void testBuiltinCorrelationProvider() {
    System.setProperty("dd.trace.enabled", "true");
    CorrelationAccess instance = CorrelationAccess.instance();
    assertTrue(instance.isAvailable());
    assertNotNull(instance.getSpanId());
    assertNotNull(instance.getTraceId());
  }

  @Test
  void testMissingCorrelationProvider() {
    CorrelationAccess instance = new CorrelationAccess(null, null);
    assertFalse(instance.isAvailable());
    assertNull(instance.getSpanId());
    assertNull(instance.getTraceId());
  }
}
