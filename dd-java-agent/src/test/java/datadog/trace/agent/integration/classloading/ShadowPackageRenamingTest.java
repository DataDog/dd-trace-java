package datadog.trace.agent.integration.classloading;

import static datadog.trace.agent.test.IntegrationTestUtils.getAgentClassLoader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.util.regex.Pattern;
import okio.BufferedSink;
import org.junit.jupiter.api.Test;

class ShadowPackageRenamingTest {
  @Test
  void agentDependenciesRenamed() throws ClassNotFoundException {
    // setup
    Class<?> ddClass =
        getAgentClassLoader().loadClass("datadog.trace.agent.tooling.AgentInstaller");
    URL userOkio = BufferedSink.class.getProtectionDomain().getCodeSource().getLocation();
    URL agentOkioDep =
        ddClass
            .getClassLoader()
            .loadClass("datadog.okio.BufferedSink")
            .getProtectionDomain()
            .getCodeSource()
            .getLocation();
    URL agentSource = ddClass.getProtectionDomain().getCodeSource().getLocation();

    // expect
    assertTrue(
        Pattern.compile(".*/dd-java-agent/build/libs/dd-java-agent-.*.jar")
            .matcher(agentSource.getFile())
            .find(),
        agentSource.getFile());
    assertEquals("file", agentSource.getProtocol());
    assertEquals(agentSource, agentOkioDep);
    assertNotEquals(userOkio.getFile(), agentSource.getFile());
  }

  @Test
  void agentClassesNotVisible() {
    assertThrows(
        ClassNotFoundException.class,
        () ->
            ClassLoader.getSystemClassLoader()
                .loadClass("datadog.trace.agent.tooling.AgentInstaller"));
  }
}
