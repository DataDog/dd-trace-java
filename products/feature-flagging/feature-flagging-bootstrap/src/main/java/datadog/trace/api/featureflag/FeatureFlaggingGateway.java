package datadog.trace.api.featureflag;

import java.io.IOException;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * Bridges the Feature Flags SDK instrumentation helpers to the agent Feature Flags backend.
 *
 * <p>Both sides ship in the agent jar, so this contract is internal to the agent and only carries
 * JDK types. The cross-version contract with the SDK is owned by the SDK and checked by muzzle.
 */
public final class FeatureFlaggingGateway {
  private static volatile Backend backend;

  private FeatureFlaggingGateway() {}

  /**
   * Registers the agent backend.
   *
   * @param registered the backend, or {@code null} to unregister it.
   */
  public static void register(@Nullable final Backend registered) {
    backend = registered;
  }

  /**
   * @return the agent backend, or {@code null} if Feature Flags is not started.
   */
  @Nullable
  public static Backend backend() {
    return backend;
  }

  /** The agent services exposed to the Feature Flags SDK. */
  public interface Backend {
    /**
     * Looks up a setting from the agent configuration.
     *
     * @param key the setting key, using the {@code dd.} system property notation without prefix.
     * @return the setting value, or {@code null} if not set.
     */
    @Nullable
    String setting(String key);

    /**
     * @return whether Remote Configuration is available.
     */
    boolean isRemoteConfigAvailable();

    /**
     * Subscribes to the Feature Flags Remote Configuration product.
     *
     * @param listener the listener receiving raw configuration documents, or {@code null} when the
     *     configuration is removed.
     * @return the handle to close to unsubscribe.
     */
    AutoCloseable subscribeRemoteConfig(Consumer<byte[]> listener);

    /**
     * @return whether the Datadog Agent event platform proxy is available.
     */
    boolean isEventProxyAvailable();

    /**
     * Posts an event payload through the Datadog Agent event platform proxy.
     *
     * @param route the event platform route.
     * @param json the UTF-8 JSON payload.
     * @return {@code true} if the payload was delivered, {@code false} if the proxy definitively
     *     rejected it as unavailable.
     * @throws IOException if the payload could not be delivered.
     */
    boolean postEvent(String route, byte[] json) throws IOException;

    /**
     * Increments a health counter.
     *
     * @param metric the metric name.
     * @param value the increment.
     * @param reason the optional {@code reason} tag value.
     */
    void count(String metric, long value, @Nullable String reason);

    /**
     * Records an evaluation that resolved to a split with a serial id on the local root span.
     *
     * @param serialId the split serial id.
     * @param doLog whether the allocation logs exposures.
     * @param targetingKey the optional targeting key.
     */
    void enrichSerialId(int serialId, boolean doLog, @Nullable String targetingKey);

    /**
     * Records an evaluation that resolved to its runtime default on the local root span.
     *
     * @param flagKey the flag key.
     * @param defaultValue the native default value.
     */
    void enrichRuntimeDefault(String flagKey, @Nullable Object defaultValue);
  }
}
