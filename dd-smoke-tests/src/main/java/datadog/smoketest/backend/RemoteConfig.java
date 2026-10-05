package datadog.smoketest.backend;

import datadog.trace.test.util.PollingConditions;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * This class represents the remote configuration capabilities of the {@link AgentBackend}. It
 * allows to push a config payload the app's tracer, that it will receive on its next {@code
 * /v0.7/config} poll, and read back the tracer's poll requests.
 */
public final class RemoteConfig {
  private static final double DEFAULT_TIMEOUT_SECONDS = 30;

  private final BiConsumer<String, String> setter;
  private final Supplier<List<Map<String, Object>>> requests;

  RemoteConfig(BiConsumer<String, String> setter, Supplier<List<Map<String, Object>>> requests) {
    this.setter = setter;
    this.requests = requests;
  }

  /**
   * Set a Remote Configuration response payload to the app's tracer on its next {@code
   * /v0.7/config} poll.
   *
   * @param path The RC target path (e.g. {@code datadog/2/APM_TRACING/config_overrides/config}).
   * @param config The config content as a JSON object literal (e.g. {@code
   *     {"asm":{"enabled":true}}}).
   */
  public void setConfig(String path, String config) {
    this.setter.accept(path, config);
  }

  /**
   * Returns the tracer's captured Remote Config poll requests (most recent first), each a decoded
   * JSON map of the poll body.
   *
   * @return The captured poll request bodies.
   */
  public List<Map<String, Object>> requests() {
    return this.requests.get();
  }

  /**
   * Waits up to {@value #DEFAULT_TIMEOUT_SECONDS}s for a captured Remote Config poll request
   * satisfying the given predicate, and returns it.
   *
   * @param predicate The predicate a poll request must satisfy.
   * @return The first matching poll request.
   * @throws AssertionError If no matching request arrives before the timeout.
   */
  public Map<String, Object> waitForRequest(Predicate<Map<String, Object>> predicate) {
    return waitForRequest(predicate, DEFAULT_TIMEOUT_SECONDS);
  }

  /**
   * Waits for a captured Remote Config poll request satisfying the given predicate, and returns it.
   *
   * @param predicate The predicate a poll request must satisfy.
   * @return The first matching poll request.
   * @throws AssertionError If no matching request arrives before the timeout.
   */
  public Map<String, Object> waitForRequest(
      Predicate<Map<String, Object>> predicate, double timeoutSeconds) {
    AtomicReference<Map<String, Object>> match = new AtomicReference<>();
    new PollingConditions(timeoutSeconds)
        .eventually(
            () -> {
              for (Map<String, Object> request : requests()) {
                if (predicate.test(request)) {
                  match.set(request);
                  return;
                }
              }
              throw new AssertionError("No remote-config poll request matched yet");
            });
    return match.get();
  }

  /**
   * Decodes the products a Remote Config poll request subscribes to.
   *
   * @param request A poll request body (from {@link #requests()} or {@link #waitForRequest}).
   * @return The subscribed product names.
   */
  @SuppressWarnings("unchecked")
  public static Set<String> products(Map<String, Object> request) {
    Map<String, Object> client = (Map<String, Object>) request.get("client");
    Set<String> products = new HashSet<>();
    if (client != null && client.get("products") instanceof List) {
      for (Object product : (List<?>) client.get("products")) {
        products.add(String.valueOf(product));
      }
    }
    return products;
  }

  /**
   * Decodes the capability flags a Remote Config poll request advertises. The tracer sends them as
   * the big-endian bytes of a {@code long} (trailing zero bytes stripped); this reconstructs that
   * {@code long} so callers can test individual {@code Capabilities.CAPABILITY_*} bits.
   *
   * @param request A poll request body (from {@link #requests()} or {@link #waitForRequest}).
   * @return The advertised capabilities, as a bit set packed into a {@code long}.
   */
  @SuppressWarnings("unchecked")
  public static long capabilities(Map<String, Object> request) {
    Map<String, Object> client = (Map<String, Object>) request.get("client");
    if (client == null || !(client.get("capabilities") instanceof List)) {
      return 0L;
    }
    List<?> bytes = (List<?>) client.get("capabilities");
    long capabilities = 0L;
    int size = bytes.size();
    for (int i = 0; i < size; i++) {
      long value = ((Number) bytes.get(i)).longValue() & 0xFF;
      capabilities |= value << ((size - i - 1) * 8);
    }
    return capabilities;
  }
}
