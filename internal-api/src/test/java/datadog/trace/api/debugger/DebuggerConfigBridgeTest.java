package datadog.trace.api.debugger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.InstrumenterConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DebuggerConfigBridgeTest {

  @AfterEach
  public void cleanup() {
    DebuggerConfigBridge.reset();
  }

  @Test
  public void testBridgeHandlesCalls() {
    MockDebuggerConfigUpdater updater = new MockDebuggerConfigUpdater();

    assertFalse(DebuggerConfigBridge.isDynamicInstrumentationEnabled());
    assertFalse(DebuggerConfigBridge.isExceptionReplayEnabled());
    assertEquals(
        InstrumenterConfig.getDefaultCodeOriginForSpanEnabled(),
        DebuggerConfigBridge.isCodeOriginEnabled());
    assertFalse(DebuggerConfigBridge.isDistributedDebuggerEnabled());

    DebuggerConfigBridge.setUpdater(updater);
    DebuggerConfigBridge.updateConfig(new DebuggerConfigUpdate(null, true, null, null));

    assertEquals(1, updater.calls);
    assertTrue(DebuggerConfigBridge.isExceptionReplayEnabled());

    DebuggerConfigBridge.updateConfig(new DebuggerConfigUpdate(true, null, null, null));

    assertEquals(2, updater.calls);
    assertTrue(DebuggerConfigBridge.isExceptionReplayEnabled());
    assertTrue(DebuggerConfigBridge.isDynamicInstrumentationEnabled());

    DebuggerConfigBridge.updateConfig(new DebuggerConfigUpdate(false, false, false, false));

    assertEquals(3, updater.calls);
    assertFalse(DebuggerConfigBridge.isDynamicInstrumentationEnabled());
    assertFalse(DebuggerConfigBridge.isExceptionReplayEnabled());
    assertFalse(DebuggerConfigBridge.isCodeOriginEnabled());
    assertFalse(DebuggerConfigBridge.isDistributedDebuggerEnabled());
  }

  @Test
  public void testBridgeResetToInitialConfig() {
    MockDebuggerConfigUpdater updater = new MockDebuggerConfigUpdater();
    DebuggerConfigBridge.setUpdater(updater);
    DebuggerConfigBridge.updateConfig(new DebuggerConfigUpdate(true, false, false, false));
    assertEquals(1, updater.calls);
    assertTrue(DebuggerConfigBridge.isDynamicInstrumentationEnabled());
    DebuggerConfigBridge.resetToInitialConfig();
    assertFalse(DebuggerConfigBridge.isDynamicInstrumentationEnabled());
  }

  @Test
  public void testUpdateConfigIgnoresEmptyUpdate() {
    MockDebuggerConfigUpdater updater = new MockDebuggerConfigUpdater();
    DebuggerConfigBridge.setUpdater(updater);
    DebuggerConfigBridge.updateConfig(new DebuggerConfigUpdate(true, false, false, false));
    assertEquals(1, updater.calls);
    assertTrue(DebuggerConfigBridge.isDynamicInstrumentationEnabled());

    DebuggerConfigBridge.updateConfig(DebuggerConfigUpdate.EMPTY);

    assertEquals(1, updater.calls);
    assertTrue(DebuggerConfigBridge.isDynamicInstrumentationEnabled());
  }

  @Test
  public void testBridgeHandlesDeferredUpdates() {
    MockDebuggerConfigUpdater updater = new MockDebuggerConfigUpdater();

    DebuggerConfigBridge.updateConfig(new DebuggerConfigUpdate(null, false, null, null));
    DebuggerConfigBridge.updateConfig(new DebuggerConfigUpdate(true, true, true, true));
    DebuggerConfigBridge.updateConfig(new DebuggerConfigUpdate(null, false, null, null));

    assertEquals(0, updater.calls);

    DebuggerConfigBridge.setUpdater(updater);

    assertEquals(1, updater.calls);
    assertTrue(DebuggerConfigBridge.isDynamicInstrumentationEnabled());
    assertFalse(DebuggerConfigBridge.isExceptionReplayEnabled());
    assertTrue(DebuggerConfigBridge.isCodeOriginEnabled());
    assertTrue(DebuggerConfigBridge.isDistributedDebuggerEnabled());
  }

  private static class MockDebuggerConfigUpdater implements DebuggerConfigUpdater {
    private int calls = 0;
    private boolean dynamicInstrumentationEnabled;
    private boolean exceptionReplayEnabled;
    private boolean codeOriginEnabled;
    private boolean distributedDebuggerEnabled;

    @Override
    public void updateConfig(DebuggerConfigUpdate update) {
      calls++;
      dynamicInstrumentationEnabled =
          update.getDynamicInstrumentationEnabled() != null
              ? update.getDynamicInstrumentationEnabled()
              : dynamicInstrumentationEnabled;
      exceptionReplayEnabled =
          update.getExceptionReplayEnabled() != null
              ? update.getExceptionReplayEnabled()
              : exceptionReplayEnabled;
      codeOriginEnabled =
          update.getCodeOriginEnabled() != null ? update.getCodeOriginEnabled() : codeOriginEnabled;
      distributedDebuggerEnabled =
          update.getDistributedDebuggerEnabled() != null
              ? update.getDistributedDebuggerEnabled()
              : distributedDebuggerEnabled;
    }

    @Override
    public boolean isDynamicInstrumentationEnabled() {
      return dynamicInstrumentationEnabled;
    }

    @Override
    public boolean isExceptionReplayEnabled() {
      return exceptionReplayEnabled;
    }

    @Override
    public boolean isCodeOriginEnabled() {
      return codeOriginEnabled;
    }

    @Override
    public boolean isDistributedDebuggerEnabled() {
      return distributedDebuggerEnabled;
    }
  }
}
