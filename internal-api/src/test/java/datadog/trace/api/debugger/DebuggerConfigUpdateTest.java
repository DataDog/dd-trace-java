package datadog.trace.api.debugger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DebuggerConfigUpdateTest {

  @Test
  public void testUpdateCoalesce() {
    DebuggerConfigUpdate existing = new DebuggerConfigUpdate(false, true, null, null);
    DebuggerConfigUpdate update = new DebuggerConfigUpdate(null, false, true, null);

    DebuggerConfigUpdate result = DebuggerConfigUpdate.coalesce(existing, update);

    assertFalse(result.getDynamicInstrumentationEnabled());
    assertFalse(result.getExceptionReplayEnabled());
    assertTrue(result.getCodeOriginEnabled());
    assertNull(result.getDistributedDebuggerEnabled());
  }
}
