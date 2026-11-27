package datadog.communication.ddagent;

import static datadog.environment.JavaVirtualMachine.isJavaVersionAtLeast;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.remoteconfig.DefaultConfigurationPoller;
import datadog.remoteconfig.WasmConfigurationPoller;
import datadog.trace.api.Config;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import org.junit.jupiter.api.Test;

/** Verifies the remote-config engine selection in {@link SharedCommunicationObjects}. */
class SharedCommunicationObjectsPollerSelectionTest extends DDJavaSpecification {

  @Test
  void buildsTheWasmEngineByDefaultOnJvm11Plus() {
    SharedCommunicationObjects sharedCommunicationObjects = new SharedCommunicationObjects();
    assertEquals(
        isJavaVersionAtLeast(11) ? WasmConfigurationPoller.class : DefaultConfigurationPoller.class,
        sharedCommunicationObjects.configurationPoller(Config.get()).getClass());
  }

  @Test
  @WithConfig(key = "remote_config.wasm.enabled", value = "false")
  void buildsTheJavaEngineWhenExplicitlyDisabled() {
    SharedCommunicationObjects sharedCommunicationObjects = new SharedCommunicationObjects();
    assertEquals(
        DefaultConfigurationPoller.class,
        sharedCommunicationObjects.configurationPoller(Config.get()).getClass());
  }
}
