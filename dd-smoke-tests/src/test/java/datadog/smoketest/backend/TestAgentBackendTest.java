package datadog.smoketest.backend;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.server.http.JavaTestHttpServer;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Docker-free unit tests for {@link TestAgentBackend} configuration/lifecycle guards. The container
 * and session-capture behavior is exercised end-to-end against a real agent in {@link
 * TestAgentBackendContainerTest}.
 */
class TestAgentBackendTest {

  @Test
  void sessionTokenIsStableAndNonEmpty() {
    TestAgentBackend backend = AgentBackend.testAgentBuilder().build();
    String token = backend.sessionToken();
    assertNotNull(token);
    assertFalse(token.isEmpty());
    assertEquals(token, backend.sessionToken(), "token is stable across calls");
  }

  @Test
  void sessionTokenCanBeOverridden() {
    assertEquals(
        "fixed-token",
        AgentBackend.testAgentBuilder().sessionToken("fixed-token").build().sessionToken(),
        "explicit token wins over the auto-generated one");
  }

  @Test
  void isSharedInferredFromExtensionRegistration() {
    // isShared() is inferred from JUnit invoking the extension's beforeAll callback (i.e. the
    // backend was declared as a @RegisterExtension field), not a manual flag. A stub agent lets us
    // drive the external lifecycle without Docker; beforeAll(...) is what JUnit calls for a
    // registered extension.
    try (JavaTestHttpServer agent = stubAgent(200, "")) {
      TestAgentBackend backend =
          AgentBackend.testAgentBuilder()
              .external(agent.getAddress().getHost(), agent.getAddress().getPort())
              .build();
      assertFalse(backend.isShared(), "not shared until registered as an extension");
      backend.beforeAll(null);
      try {
        assertTrue(backend.isShared(), "inferred shared once its beforeAll callback runs");
      } finally {
        backend.close();
      }
    }
  }

  @Test
  void accessBeforeStartFails() {
    TestAgentBackend backend = AgentBackend.testAgentBuilder().build();
    assertThrows(IllegalStateException.class, backend::url, "url() before start()");
    assertThrows(IllegalStateException.class, backend::port, "port() before start()");
  }

  @Test
  void assertNoInvariantFailuresPassesWhenAgentReportsNoFailures() {
    // A stub agent for /test/session/* and /test/trace_check/failures verifies the check logic
    // without Docker; HTTP 200 from the failures endpoint means all checks passed.
    try (JavaTestHttpServer agent = stubAgent(200, "")) {
      TestAgentBackend backend =
          AgentBackend.testAgentBuilder()
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
          AgentBackend.testAgentBuilder()
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

  @Test
  void setRemoteConfigPostsPathAndConfigToTheSession() {
    // The backend POSTs {"path": ..., "msg": <config>} to /test/session/responses/config/path so
    // the agent builds the signed RC envelope; capture that request against a stub agent.
    AtomicReference<String> captured = new AtomicReference<>();
    try (JavaTestHttpServer agent =
        JavaTestHttpServer.httpServer(
            server ->
                server.handlers(
                    handlers -> {
                      handlers.prefix(
                          "/test/session/responses/config/path",
                          api -> {
                            captured.set(new String(api.getRequest().getBody(), UTF_8));
                            api.getResponse().status(202).send();
                          });
                      handlers.all(api -> api.getResponse().status(200).send());
                    }))) {
      TestAgentBackend backend =
          AgentBackend.testAgentBuilder()
              .external(agent.getAddress().getHost(), agent.getAddress().getPort())
              .build();
      backend.start();
      try {
        backend
            .remoteConfig()
            .setConfig(
                "datadog/2/APM_TRACING/config_overrides/config", "{\"lib_config\":{\"x\":1}}");
      } finally {
        backend.close();
      }
      String body = captured.get();
      assertNotNull(body, "agent received a config-path POST");
      assertTrue(body.contains("\"path\":\"datadog/2/APM_TRACING/config_overrides/config\""), body);
      assertTrue(body.contains("\"msg\":{\"lib_config\":{\"x\":1}}"), body);
    }
  }

  @Test
  void readsAndDecodesRemoteConfigPollRequests() {
    // /test/session/requests returns every request the tracer made, each with a base64-encoded
    // body. Serve one /v0.7/config poll (and a non-RC request that must be filtered out) and assert
    // the backend selects, decodes, and exposes the poll's products and capabilities.
    String pollBody =
        "{\"client\":{\"products\":[\"APM_TRACING\",\"ASM_FEATURES\"],\"capabilities\":[2]}}";
    String encoded = Base64.getEncoder().encodeToString(pollBody.getBytes(UTF_8));
    String requestsJson =
        "[{\"url\":\"http://agent/v0.7/config\",\"method\":\"POST\",\"body\":\""
            + encoded
            + "\"},{\"url\":\"http://agent/v0.6/stats\",\"method\":\"POST\",\"body\":\"\"}]";
    try (JavaTestHttpServer agent =
        JavaTestHttpServer.httpServer(
            server ->
                server.handlers(
                    handlers -> {
                      handlers.prefix(
                          "/test/session/requests",
                          api -> api.getResponse().status(200).send(requestsJson));
                      handlers.all(api -> api.getResponse().status(200).send());
                    }))) {
      TestAgentBackend backend =
          AgentBackend.testAgentBuilder()
              .external(agent.getAddress().getHost(), agent.getAddress().getPort())
              .build();
      backend.start();
      try {
        List<Map<String, Object>> polls = backend.remoteConfig().requests();
        assertEquals(1, polls.size(), "only /v0.7/config polls are returned");
        assertTrue(
            RemoteConfig.products(polls.get(0)).contains("ASM_FEATURES"),
            "products decoded from the poll body");
        assertEquals(
            2L, RemoteConfig.capabilities(polls.get(0)), "capabilities decoded big-endian");
      } finally {
        backend.close();
      }
    }
  }

  @Test
  void clearResetsRemoteConfigBeforeOpeningTheSession() {
    // clear() resets the session's RC response to empty ({}) AND opens a fresh session, so a config
    // pushed by one test does not leak into the next. The reset must come first: a tracer poll
    // between the two calls would otherwise record the stale config in the new session.
    AtomicReference<String> captured = new AtomicReference<>();
    List<String> calls = new CopyOnWriteArrayList<>();
    try (JavaTestHttpServer agent =
        JavaTestHttpServer.httpServer(
            server ->
                server.handlers(
                    handlers -> {
                      handlers.prefix(
                          "/test/session/responses/config",
                          api -> {
                            captured.set(new String(api.getRequest().getBody(), UTF_8));
                            calls.add("reset");
                            api.getResponse().status(202).send();
                          });
                      handlers.prefix(
                          "/test/session/start",
                          api -> {
                            calls.add("start");
                            api.getResponse().status(200).send();
                          });
                      handlers.all(api -> api.getResponse().status(200).send());
                    }))) {
      TestAgentBackend backend =
          AgentBackend.testAgentBuilder()
              .external(agent.getAddress().getHost(), agent.getAddress().getPort())
              .build();
      backend.start(); // start() opens the first session via clear()
      try {
        assertEquals("{}", captured.get(), "clear() resets the RC response to the empty default");
        assertEquals(asList("reset", "start"), calls, "RC reset precedes the session start");
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
