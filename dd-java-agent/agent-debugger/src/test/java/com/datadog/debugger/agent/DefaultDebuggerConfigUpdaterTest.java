package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import java.lang.instrument.Instrumentation;
import org.junit.jupiter.api.Test;

class DefaultDebuggerConfigUpdaterTest {

  @Test
  public void enableDisable() {
    SharedCommunicationObjects sco = mock(SharedCommunicationObjects.class);
    when(sco.featuresDiscovery(any())).thenReturn(mock(DDAgentFeaturesDiscovery.class));
    DebuggerAgent.run(mock(Instrumentation.class), sco);
    DefaultDebuggerConfigUpdater productConfigUpdater = new DefaultDebuggerConfigUpdater();
    productConfigUpdater.updateConfig(null);
    productConfigUpdater.updateConfig(true);
    assertTrue(productConfigUpdater.isDynamicInstrumentationEnabled());
    assertTrue(productConfigUpdater.isExceptionReplayEnabled());
    assertTrue(productConfigUpdater.isCodeOriginEnabled());
    assertTrue(productConfigUpdater.isDistributedDebuggerEnabled());
    productConfigUpdater.updateConfig(null);
    assertTrue(productConfigUpdater.isDynamicInstrumentationEnabled());
    assertTrue(productConfigUpdater.isExceptionReplayEnabled());
    assertTrue(productConfigUpdater.isCodeOriginEnabled());
    assertTrue(productConfigUpdater.isDistributedDebuggerEnabled());
    productConfigUpdater.updateConfig(false);
    assertFalse(productConfigUpdater.isDynamicInstrumentationEnabled());
    assertFalse(productConfigUpdater.isExceptionReplayEnabled());
    assertFalse(productConfigUpdater.isCodeOriginEnabled());
    assertFalse(productConfigUpdater.isDistributedDebuggerEnabled());
  }
}
