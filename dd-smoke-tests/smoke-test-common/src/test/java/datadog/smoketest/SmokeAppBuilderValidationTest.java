package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.smoketest.backend.AgentBackend;
import org.junit.jupiter.api.Test;

/** Docker-free unit tests for {@link AbstractSmokeApp}'s builder validation. */
class SmokeAppBuilderValidationTest {
  private static final String AGENT_JAR_PROPERTY = "datadog.smoketest.agent.shadowJar.path";

  @Test
  void buildRequiresAnAgentJarUnlessNoAgentOrExplicitAgent() {
    String saved = System.getProperty(AGENT_JAR_PROPERTY);
    System.clearProperty(AGENT_JAR_PROPERTY);
    try {
      // No noAgent()/javaAgent() and no agent-jar property: fail loudly rather than silently launch
      // without the tracer (which would also skip the telemetry check and pass app-only
      // assertions).
      IllegalStateException error =
          assertThrows(
              IllegalStateException.class,
              () ->
                  SmokeServerApp.named("no-agent-jar")
                      .mainClass("datadog.smoketest.TestServerApp")
                      .backend(AgentBackend.mockAgent())
                      .build());
      assertTrue(error.getMessage().contains("Agent jar not found"), error.getMessage());

      // noAgent() is a valid way to build without an agent jar.
      assertDoesNotThrow(
          () ->
              SmokeServerApp.named("no-agent")
                  .mainClass("datadog.smoketest.TestServerApp")
                  .backend(AgentBackend.mockAgent())
                  .noAgent()
                  .build(),
          "noAgent() should build without an agent jar");

      // An explicit javaAgent(path) is also valid without the property.
      assertDoesNotThrow(
          () ->
              SmokeServerApp.named("explicit-agent")
                  .mainClass("datadog.smoketest.TestServerApp")
                  .backend(AgentBackend.mockAgent())
                  .javaAgent("/tmp/some-agent.jar")
                  .build(),
          "an explicit agent jar should build without the property");
    } finally {
      if (saved != null) {
        System.setProperty(AGENT_JAR_PROPERTY, saved);
      }
    }
  }
}
