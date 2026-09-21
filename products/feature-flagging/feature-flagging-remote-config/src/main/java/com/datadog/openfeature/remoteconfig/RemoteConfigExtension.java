package com.datadog.openfeature.remoteconfig;

import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.remoteconfig.Capabilities;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.Product;
import datadog.trace.api.Config;
import datadog.trace.api.featureflag.RemoteConfigTransport;
import datadog.trace.api.intake.Intake;
import java.io.IOException;
import java.util.function.Function;
import okhttp3.MediaType;
import okhttp3.RequestBody;

/**
 * Optional standalone RC composition. Reuses the same RC client implementation as the Java agent.
 */
public final class RemoteConfigExtension implements RemoteConfigTransport {
  private static final MediaType JSON = MediaType.parse("application/json");
  private final ConfigurationPoller poller;
  private final Function<Boolean, BackendApi> proxyFactory;
  private final Object lock = new Object();
  private BackendApi compressedProxy;
  private BackendApi plainProxy;
  private boolean started;
  private boolean closed;

  private RemoteConfigExtension(
      ConfigurationPoller poller, Function<Boolean, BackendApi> proxyFactory) {
    this.poller = poller;
    this.proxyFactory = proxyFactory;
  }

  /** Loaded only when standalone explicitly selects RC and the Java agent does not own delivery. */
  public static RemoteConfigTransport create() {
    final Config config = Config.get();
    if (!config.isRemoteConfigEnabled()) {
      throw new IllegalStateException(
          "Feature Flags remote_config requires DD_REMOTE_CONFIGURATION_ENABLED=true");
    }
    final SharedCommunicationObjects communication = new SharedCommunicationObjects();
    communication.createRemaining(config);
    final BackendApiFactory factory = new BackendApiFactory(config, communication);
    return new RemoteConfigExtension(
        communication.configurationPoller(config),
        compression -> factory.createEvpProxyApi(Intake.EVENT_PLATFORM, compression));
  }

  // Test seam; no alternate RC client implementation.
  static RemoteConfigExtension create(
      ConfigurationPoller poller, Function<Boolean, BackendApi> proxyFactory) {
    return new RemoteConfigExtension(poller, proxyFactory);
  }

  @Override
  public void start(ConfigurationListener listener) {
    synchronized (lock) {
      if (closed) {
        throw new IllegalStateException("Feature Flags RC transport is closed");
      }
      if (started) {
        return;
      }
      // Record ownership before starting so rollback closes partial initialization.
      started = true;
      poller.addCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
      poller.addListener(Product.FFE_FLAGS, (key, bytes, hinter) -> listener.accept(bytes));
      poller.start();
    }
  }

  @Override
  public void post(String route, byte[] json, boolean responseCompression) throws IOException {
    final BackendApi proxy = proxy(responseCompression);
    if (proxy == null) {
      throw new IOException(
          "Datadog Agent EVP proxy is unavailable; direct intake fallback is disabled");
    }
    proxy.post(route, RequestBody.create(JSON, json), stream -> null, null, false);
  }

  private BackendApi proxy(boolean responseCompression) throws IOException {
    synchronized (lock) {
      if (closed) {
        throw new IOException("Feature Flags RC transport is closed");
      }
      if (responseCompression) {
        if (compressedProxy == null) {
          compressedProxy = proxyFactory.apply(true);
        }
        return compressedProxy;
      }
      if (plainProxy == null) {
        plainProxy = proxyFactory.apply(false);
      }
      return plainProxy;
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      if (started) {
        poller.removeCapabilities(Capabilities.CAPABILITY_FFE_FLAG_CONFIGURATION_RULES);
        poller.removeListeners(Product.FFE_FLAGS);
        poller.stop();
      }
    }
  }
}
