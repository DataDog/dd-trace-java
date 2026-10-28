package datadog.smoketest.backend;

import static datadog.smoketest.backend.Polling.waitForFirst;
import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.agent.test.server.http.JavaTestHttpServer.Headers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * This class is a query facade over the requests the tracer sent through the event platform proxy
 * ({@code /evp_proxy/v2/}) of an {@link AgentBackend}.
 */
public final class EvpProxy {
  private static final double DEFAULT_TIMEOUT_SECONDS = 30;

  private final Supplier<List<Request>> source;

  EvpProxy(Supplier<List<Request>> source) {
    this.source = source;
  }

  /**
   * Returns the captured requests, in arrival order.
   *
   * @return The captured requests.
   */
  public List<Request> requests() {
    return this.source.get();
  }

  /**
   * Returns the captured requests to an intake path, in arrival order.
   *
   * @param path The intake path, after the proxy prefix (e.g. {@code api/v2/exposures}).
   * @return The captured requests to this path.
   */
  public List<Request> requests(String path) {
    List<Request> matching = new ArrayList<>();
    for (Request request : requests()) {
      if (path.equals(request.path())) {
        matching.add(request);
      }
    }
    return matching;
  }

  /**
   * Waits up to {@value #DEFAULT_TIMEOUT_SECONDS}s for a captured request satisfying the given
   * predicate, and returns it.
   *
   * @param predicate The predicate a request must satisfy.
   * @return The first matching request.
   * @throws AssertionError If no matching request arrives before the timeout.
   */
  public Request waitForRequest(Predicate<Request> predicate) {
    return waitForRequest(predicate, DEFAULT_TIMEOUT_SECONDS);
  }

  /**
   * Waits for a captured request satisfying the given predicate, and returns it.
   *
   * @param predicate The predicate a request must satisfy.
   * @param timeoutSeconds How long to wait, in seconds.
   * @return The first matching request.
   * @throws AssertionError If no matching request arrives before the timeout.
   */
  public Request waitForRequest(Predicate<Request> predicate, double timeoutSeconds) {
    return waitForFirst(this::requests, predicate, timeoutSeconds, "event platform proxy request");
  }

  /** A request the tracer sent through the event platform proxy. */
  public static final class Request {
    private final String path;
    private final Headers headers;
    private final byte[] body;

    Request(String path, Headers headers, byte[] body) {
      this.path = path;
      this.headers = headers;
      this.body = body;
    }

    /**
     * Returns the intake path, after the proxy prefix.
     *
     * @return The intake path (e.g. {@code api/v2/exposures}).
     */
    public String path() {
      return this.path;
    }

    /**
     * Returns a request header.
     *
     * @param name The header name, as sent by the tracer.
     * @return The header value, or {@code null} if the request has no such header.
     */
    public String header(String name) {
      return this.headers.get(name);
    }

    /**
     * Returns the request body.
     *
     * @return The request body, as UTF-8 text.
     */
    public String body() {
      return new String(this.body, UTF_8);
    }

    /**
     * Decodes the request body as a JSON object.
     *
     * @return The decoded body.
     */
    public Map<String, Object> json() {
      return AgentBackendMessages.decodeMessage(this.body);
    }
  }
}
