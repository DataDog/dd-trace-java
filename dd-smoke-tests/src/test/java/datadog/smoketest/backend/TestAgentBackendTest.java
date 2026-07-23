package datadog.smoketest.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.server.http.JavaTestHttpServer;
import org.junit.jupiter.api.Test;

/**
 * Docker-free unit tests for {@link TestAgentBackend} configuration/lifecycle guards. The container
 * and session-capture behavior is exercised end-to-end against a real agent in {@link
 * TestAgentBackendContainerTest}.
 */
class TestAgentBackendTest {

  @Test
  void sessionTokenIsStableAndNonEmpty() {
    TestAgentBackend backend = TraceBackend.testAgentBuilder().build();
    String token = backend.sessionToken();
    assertNotNull(token);
    assertFalse(token.isEmpty());
    assertEquals(token, backend.sessionToken(), "token is stable across calls");
  }

  @Test
  void sessionTokenCanBeOverridden() {
    assertEquals(
        "fixed-token",
        TraceBackend.testAgentBuilder().sessionToken("fixed-token").build().sessionToken(),
        "explicit token wins over the auto-generated one");
  }

  @Test
  void sharedFlagDefaultsFalseAndIsSettable() {
    assertFalse(TraceBackend.testAgentBuilder().build().isShared(), "not shared by default");
    assertTrue(TraceBackend.testAgentBuilder().shared().build().isShared(), "shared() opts in");
  }

  @Test
  void accessBeforeStartFails() {
    TestAgentBackend backend = TraceBackend.testAgentBuilder().build();
    assertThrows(IllegalStateException.class, backend::url, "url() before start()");
    assertThrows(IllegalStateException.class, backend::port, "port() before start()");
  }

  @Test
  void assertNoInvariantFailuresPassesWhenAgentReportsNoFailures() {
    // A stub agent for /test/session/* and /test/trace_check/failures verifies the check logic
    // without Docker; HTTP 200 from the failures endpoint means all checks passed.
    try (JavaTestHttpServer agent = stubAgent(200, "")) {
      TestAgentBackend backend =
          TraceBackend.testAgentBuilder()
              .external(agent.getAddress().getHost(), agent.getAddress().getPort())
              .build();
      backend.start();
      try {
        backend.assertNoInvariantFailures(); // HTTP 200 => no failures => no throw
      } finally {
        backend.close();
      }
    }
  }

  @Test
  void assertNoInvariantFailuresThrowsWhenAgentReportsFailures() {
    try (JavaTestHttpServer agent = stubAgent(400, "span_count check failed")) {
      TestAgentBackend backend =
          TraceBackend.testAgentBuilder()
              .external(agent.getAddress().getHost(), agent.getAddress().getPort())
              .build();
      backend.start();
      try {
        AssertionError error =
            assertThrows(AssertionError.class, backend::assertNoInvariantFailures);
        assertTrue(error.getMessage().contains("span_count check failed"), error.getMessage());
      } finally {
        backend.close();
      }
    }
  }

  /** A stub test agent: 200 on {@code /test/session/start}, {@code failuresStatus} on failures. */
  private static JavaTestHttpServer stubAgent(int failuresStatus, String failuresBody) {
    return JavaTestHttpServer.httpServer(
        server ->
            server.handlers(
                handlers -> {
                  handlers.prefix(
                      "/test/session/start", api -> api.getResponse().status(200).send());
                  handlers.prefix(
                      "/test/trace_check/failures",
                      api -> {
                        if (failuresBody.isEmpty()) {
                          api.getResponse().status(failuresStatus).send();
                        } else {
                          api.getResponse().status(failuresStatus).send(failuresBody);
                        }
                      });
                  handlers.all(api -> api.getResponse().status(200).send());
                }));
  }
}
