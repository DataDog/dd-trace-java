package datadog.trace.bootstrap.config.provider.civisibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class CiEnvironmentVariablesTest {

  private static final String SECRET_KEY = "secret-key";

  private static MockWebServer server;

  private static final AtomicInteger failedResponses = new AtomicInteger(0);

  @BeforeAll
  public static void startServer() throws Exception {
    server = new MockWebServer();
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest req) {
            if (failedResponses.getAndDecrement() > 0) {
              return new MockResponse().setResponseCode(500);
            }
            if (SECRET_KEY.equals(
                req.getHeader(CiEnvironmentVariables.DD_ENV_VARS_PROVIDER_KEY_HEADER))) {
              return new MockResponse().setResponseCode(200).setBody("a=1\nb=2");
            }
            return new MockResponse().setResponseCode(403);
          }
        });
    server.start();
  }

  @AfterAll
  public static void stopServer() throws Exception {
    server.shutdown();
  }

  @Test
  void testGetEnvironment() {
    failedResponses.set(1); // to test retries

    Map<String, String> env =
        CiEnvironmentVariables.getRemoteEnvironmentWithRetries(
            server.url("/").toString(),
            SECRET_KEY,
            new CiEnvironmentVariables.RetryPolicy(2, 3, 2),
            null);
    assertEquals(2, env.size());
    assertEquals("1", env.get("a"));
    assertEquals("2", env.get("b"));
  }

  @Test
  void testFailedGetEnvironment() {
    failedResponses.set(3);

    Map<String, String> env =
        CiEnvironmentVariables.getRemoteEnvironmentWithRetries(
            server.url("/").toString(),
            SECRET_KEY,
            new CiEnvironmentVariables.RetryPolicy(2, 3, 2),
            null);
    assertNull(env);
  }
}
