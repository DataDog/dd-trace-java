package datadog.smoketest;

import static datadog.trace.agent.test.utils.PortUtils.waitForPortToOpen;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.agent.test.utils.PortUtils;
import datadog.trace.api.internal.VisibleForTesting;
import java.io.IOException;
import java.net.URI;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * A long-running HTTP-server smoke app: it stays up across test methods, serving requests the test
 * body drives. On start-up it waits for its (randomly-allocated) port to open, and between methods
 * it asserts the process is still alive and resets its owned backend so each method sees only its
 * own traces.
 *
 * <p>The allocated port is exposed as {@link #httpPort()} and substituted into launch args via the
 * {@code ${app.httpPort}} placeholder:
 *
 * <pre>{@code
 * @RegisterExtension
 * static final SmokeServerApp app = SmokeServerApp.named("springboot")
 *     .jar(System.getProperty("datadog.smoketest.springboot.shadowJar.path"))
 *     .args("--server.port=${app.httpPort}")
 *     .backend(TraceBackend.mockAgent())
 *     .build();
 * }</pre>
 */
public final class SmokeServerApp extends AbstractSmokeApp {
  private static final String HTTP_PORT_PLACEHOLDER = "${app.httpPort}";

  private final int httpPort;
  private final OkHttpClient httpClient = new OkHttpClient();

  private SmokeServerApp(Builder builder) {
    super(builder);
    this.httpPort = PortUtils.randomOpenPort();
    registerPlaceholder(HTTP_PORT_PLACEHOLDER, () -> Integer.toString(this.httpPort));
  }

  /** Starts a fluent builder for a server app with the given (log/diagnostic) name. */
  public static Builder named(String name) {
    return new Builder(name);
  }

  /**
   * The randomly-allocated port the app should bind (substituted for {@value
   * #HTTP_PORT_PLACEHOLDER}).
   */
  public int httpPort() {
    return this.httpPort;
  }

  /** Base URL of the app's HTTP server. */
  public URI url() {
    return URI.create("http://localhost:" + this.httpPort);
  }

  /**
   * Issues a GET to the app and returns the HTTP status code (the response is drained and closed).
   */
  @VisibleForTesting
  int get(String path) {
    String full = url() + (path.startsWith("/") ? path : "/" + path);
    Request request = new Request.Builder().url(full).get().build();
    try (Response response = this.httpClient.newCall(request).execute()) {
      return response.code();
    } catch (IOException e) {
      throw new IllegalStateException("GET " + full + " failed", e);
    }
  }

  @Override
  protected void onStarted() {
    waitForPortToOpen(this.httpPort, startupTimeoutSeconds(), SECONDS, process());
  }

  @Override
  protected void onBeforeEach() {
    // A server must stay up across methods, and its per-test traces are produced during the test
    // body — so assert it's alive and reset the (owned) backend between methods.
    if (process() == null || !process().isAlive()) {
      throw new IllegalStateException("App '" + name() + "' is not alive at the start of a test");
    }
    if (ownsBackend()) {
      backend().clear();
    }
  }

  /**
   * Fluent builder for a {@link SmokeServerApp}; obtain via {@link SmokeServerApp#named(String)}.
   */
  public static final class Builder extends AbstractSmokeApp.Builder<SmokeServerApp, Builder> {
    private Builder(String name) {
      super(name);
    }

    @Override
    protected Builder self() {
      return this;
    }

    @Override
    public SmokeServerApp build() {
      validate();
      return new SmokeServerApp(this);
    }
  }
}
