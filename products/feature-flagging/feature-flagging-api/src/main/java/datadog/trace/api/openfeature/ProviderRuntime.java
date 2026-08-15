package datadog.trace.api.openfeature;

import datadog.openfeature.internal.core.ConfigurationSnapshot;
import datadog.openfeature.internal.core.ConfigurationSource;
import datadog.openfeature.internal.core.ConfigurationStore;
import datadog.openfeature.internal.http.CdnConfigurationSource;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One reference-counted configuration runtime per provider classloader. */
final class ProviderRuntime {

  private static final Object LOCK = new Object();
  private static SharedRuntime shared;

  private ProviderRuntime() {}

  static Handle acquire(
      final RuntimeConfiguration configuration, final Consumer<ConfigurationSnapshot> listener) {
    if (configuration.source == RuntimeConfiguration.Source.DISABLED) {
      throw new IllegalStateException("Datadog OpenFeature provider is disabled by configuration");
    }
    synchronized (LOCK) {
      if (shared == null) {
        shared = new SharedRuntime(configuration);
      } else if (!shared.configuration.equals(configuration)) {
        throw new IllegalStateException(
            "All Datadog OpenFeature providers in one application classloader must use "
                + "the same configuration source and options");
      }
      shared.references++;
      shared.store.addListener(listener);
      try {
        shared.start();
      } catch (final RuntimeException | Error e) {
        shared.store.removeListener(listener);
        shared.references--;
        if (shared.references == 0) {
          shared.close();
          shared = null;
        }
        throw e;
      }
      return new Handle(shared, listener);
    }
  }

  static final class Handle implements AutoCloseable {
    private SharedRuntime runtime;
    private Consumer<ConfigurationSnapshot> listener;

    private Handle(final SharedRuntime runtime, final Consumer<ConfigurationSnapshot> listener) {
      this.runtime = runtime;
      this.listener = listener;
    }

    ConfigurationSnapshot configuration() {
      return runtime == null ? null : runtime.store.current();
    }

    boolean awaitConfiguration(final long timeout, final TimeUnit unit)
        throws InterruptedException {
      return runtime != null && runtime.store.awaitConfiguration(timeout, unit);
    }

    @Override
    public void close() {
      synchronized (LOCK) {
        if (runtime == null) {
          return;
        }
        runtime.store.removeListener(listener);
        runtime.references--;
        if (runtime.references == 0) {
          runtime.close();
          if (shared == runtime) {
            shared = null;
          }
        }
        runtime = null;
        listener = null;
      }
    }
  }

  private static final class SharedRuntime {
    private final RuntimeConfiguration configuration;
    private final ConfigurationStore store = new ConfigurationStore();
    private ConfigurationSource source;
    private int references;
    private boolean started;

    private SharedRuntime(final RuntimeConfiguration configuration) {
      this.configuration = configuration;
    }

    private void start() {
      if (started) {
        return;
      }
      started = true;
      RawBridgeAccess.activateIfPresent();
      source =
          configuration.source == RuntimeConfiguration.Source.CDN
              ? new CdnConfigurationSource(configuration.http, store)
              : RawBridgeAccess.remoteConfigurationSource(store);
      source.start();
    }

    private void close() {
      if (source != null) {
        source.close();
        source = null;
      }
      store.clear();
      started = false;
    }
  }
}
