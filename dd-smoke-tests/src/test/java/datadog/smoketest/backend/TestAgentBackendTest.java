package datadog.smoketest.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Docker-free unit tests for {@link TestAgentBackend} configuration/lifecycle guards. The container
 * and session-capture behavior is exercised end-to-end against a real agent in {@link
 * TestAgentBackendContainerTest}.
 */
class TestAgentBackendTest {

  @Test
  void sessionTokenIsStableAndNonEmpty() {
    TestAgentBackend backend = TraceBackend.testAgent().build();
    String token = backend.sessionToken();
    assertNotNull(token);
    assertFalse(token.isEmpty());
    assertEquals(token, backend.sessionToken(), "token is stable across calls");
  }

  @Test
  void sessionTokenCanBeOverridden() {
    assertEquals(
        "fixed-token",
        TraceBackend.testAgent().sessionToken("fixed-token").build().sessionToken(),
        "explicit token wins over the auto-generated one");
  }

  @Test
  void sharedFlagDefaultsFalseAndIsSettable() {
    assertFalse(TraceBackend.testAgent().build().isShared(), "not shared by default");
    assertTrue(TraceBackend.testAgent().shared().build().isShared(), "shared() opts in");
  }

  @Test
  void accessBeforeStartFails() {
    TestAgentBackend backend = TraceBackend.testAgent().build();
    assertThrows(IllegalStateException.class, backend::url, "url() before start()");
    assertThrows(IllegalStateException.class, backend::port, "port() before start()");
  }
}
