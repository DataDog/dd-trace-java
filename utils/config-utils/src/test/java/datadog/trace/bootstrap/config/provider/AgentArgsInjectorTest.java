package datadog.trace.bootstrap.config.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class AgentArgsInjectorTest {

  @AfterEach
  void clearInjectedProperties() {
    System.clearProperty("arg1");
    System.clearProperty("arg2");
  }

  @Test
  void injectsAgentArgumentsAsSystemProperties() {
    String agentArgs = "arg1=value1,arg2=value2";

    AgentArgsInjector.injectAgentArgsConfig(agentArgs);

    assertEquals("value1", System.getProperty("arg1"));
    assertEquals("value2", System.getProperty("arg2"));
  }
}
