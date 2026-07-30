package datadog.trace.api.openfeature;

import datadog.openfeature.internal.core.ConfigurationSnapshot;
import datadog.openfeature.internal.core.ConfigurationSource;
import datadog.openfeature.internal.core.ConfigurationStore;
import datadog.openfeature.internal.http.CdnConfigurationSource;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One reference-counted CDN runtime per provider classloader. */
final class StandaloneProviderRuntime {

  private static final Object LOCK = new Object();
  private static SharedRuntime shared;

  private StandaloneProviderRuntime() {}

  static Handle acquire(
      final StandaloneRuntimeConfiguration configuration,
      final Consumer<ConfigurationSnapshot> listener) {
    if (configuration.source == StandaloneRuntimeConfiguration.Source.DISABLED) {
      throw new IllegalStateException("Datadog OpenFeature provider is disabled by configuration");
    }
    if (configuration.source == StandaloneRuntimeConfiguration.Source.REMOTE_CONFIG) {
      throw new IllegalStateException(
          "The remote_config source requires dd-java-agent.jar. "
              + "Use the agentless source when the Java agent is not installed.");
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
    private final StandaloneRuntimeConfiguration configuration;
    private final ConfigurationStore store = new ConfigurationStore();
    private final ConfigurationSource source;
    private int references;
    private boolean started;

    private SharedRuntime(final StandaloneRuntimeConfiguration configuration) {
      this.configuration = configuration;
      source = new CdnConfigurationSource(configuration.http, store);
    }

    private void start() {
      if (started) {
        return;
      }
      started = true;
      source.start();
    }

    private void close() {
      source.close();
      store.clear();
      started = false;
    }
  }
}
