package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DefaultProductConfigUpdaterTest {

  @Test
  public void enableDisable() {
    DefaultProductConfigUpdater productConfigUpdater = new DefaultProductConfigUpdater();
    productConfigUpdater.updateConfig(null, null, null, null);
    productConfigUpdater.updateConfig(true, true, true, true);
    assertTrue(productConfigUpdater.isDynamicInstrumentationEnabled());
    assertTrue(productConfigUpdater.isExceptionReplayEnabled());
    assertTrue(productConfigUpdater.isCodeOriginEnabled());
    assertTrue(productConfigUpdater.isDistributedDebuggerEnabled());
    productConfigUpdater.updateConfig(false, false, false, false);
    assertFalse(productConfigUpdater.isDynamicInstrumentationEnabled());
    assertFalse(productConfigUpdater.isExceptionReplayEnabled());
    assertFalse(productConfigUpdater.isCodeOriginEnabled());
    assertFalse(productConfigUpdater.isDistributedDebuggerEnabled());
  }
}
