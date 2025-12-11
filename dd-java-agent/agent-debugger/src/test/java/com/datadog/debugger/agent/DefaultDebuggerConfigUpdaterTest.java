package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.trace.api.Config;
import datadog.trace.api.debugger.DebuggerConfigUpdate;
import java.lang.instrument.Instrumentation;
import org.junit.jupiter.api.Test;

class DefaultDebuggerConfigUpdaterTest {

  @Test
  public void enableDisable() {
    SharedCommunicationObjects sco = mock(SharedCommunicationObjects.class);
    when(sco.configurationPoller(null)).thenReturn(mock(ConfigurationPoller.class));
    when(sco.featuresDiscovery(any())).thenReturn(mock(DDAgentFeaturesDiscovery.class));
    DebuggerAgent.run(Config.get(), mock(Instrumentation.class), sco);
    DefaultDebuggerConfigUpdater productConfigUpdater =
        new DefaultDebuggerConfigUpdater(Config.get());
    productConfigUpdater.updateConfig(new DebuggerConfigUpdate());
    productConfigUpdater.updateConfig(new DebuggerConfigUpdate(true, true, true, true));
    assertTrue(productConfigUpdater.isDynamicInstrumentationEnabled());
    assertTrue(productConfigUpdater.isExceptionReplayEnabled());
    assertTrue(productConfigUpdater.isCodeOriginEnabled());
    assertTrue(productConfigUpdater.isDistributedDebuggerEnabled());
    productConfigUpdater.updateConfig(new DebuggerConfigUpdate());
    assertTrue(productConfigUpdater.isDynamicInstrumentationEnabled());
    assertTrue(productConfigUpdater.isExceptionReplayEnabled());
    assertTrue(productConfigUpdater.isCodeOriginEnabled());
    assertTrue(productConfigUpdater.isDistributedDebuggerEnabled());
    productConfigUpdater.updateConfig(new DebuggerConfigUpdate(false, false, false, false));
    assertFalse(productConfigUpdater.isDynamicInstrumentationEnabled());
    assertFalse(productConfigUpdater.isExceptionReplayEnabled());
    assertFalse(productConfigUpdater.isCodeOriginEnabled());
    assertFalse(productConfigUpdater.isDistributedDebuggerEnabled());
  }
}
