package com.datadog.openfeature.internal;

import static com.datadog.openfeature.internal.config.Settings.CONFIGURATION_SOURCE_REMOTE_CONFIG;

import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.bridge.v1.ConfigurationSource;
import com.datadog.openfeature.internal.bridge.v1.EventTransport;
import com.datadog.openfeature.internal.bridge.v1.ShutdownSource;
import com.datadog.openfeature.internal.config.Settings;
import com.datadog.openfeature.internal.delivery.CdnConfigurationSource;
import com.datadog.openfeature.internal.delivery.DirectIntakeTransport;
import com.datadog.openfeature.internal.delivery.FallbackTransport;
import com.datadog.openfeature.internal.delivery.RemoteConfigurationService;
import com.datadog.openfeature.internal.exposure.ExposureDeduplicationCache;
import com.datadog.openfeature.internal.exposure.ExposureEvent;
import com.datadog.openfeature.internal.exposure.ExposurePipeline;
import com.datadog.openfeature.internal.flagevaluation.FlagEvaluationPipeline;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Feature Flags runtime: configuration delivery and event pipelines. A single runtime is shared
 * by all the providers of a class loader, and stopped when the last one releases it, or when the
 * application shuts down: by the Datadog Java agent before the JVM shutdown hooks run when
 * attached, or by a JVM shutdown hook otherwise.
 */
public final class FeatureFlagsRuntime {
  private static final Logger LOGGER = LoggerFactory.getLogger(FeatureFlagsRuntime.class);

  private static final Object SHARED_LOCK = new Object();
  private static FeatureFlagsRuntime shared;
  private static int references;

  private final ConfigurationService source;
  private final ExposureDeduplicationCache exposureCache = new ExposureDeduplicationCache();
  @Nullable private final ExposurePipeline exposures;
  @Nullable private final FlagEvaluationPipeline evaluations;
  private final List<Consumer<ServerConfiguration>> listeners = new CopyOnWriteArrayList<>();
  private volatile ServerConfiguration configuration;
  private final AtomicBoolean stopped = new AtomicBoolean();
  @Nullable private volatile AutoCloseable shutdownRegistration;

  FeatureFlagsRuntime(
      final ConfigurationService source,
      @Nullable final ExposurePipeline exposures,
      @Nullable final FlagEvaluationPipeline evaluations) {
    this.source = source;
    this.exposures = exposures;
    this.evaluations = evaluations;
  }

  /**
   * Acquires the shared runtime, starting it on first acquisition. Each acquisition must be paired
   * with a {@link #release()}.
   *
   * @param bridge the bridge to the Datadog Java agent.
   * @param settings the SDK settings.
   * @return the shared runtime.
   * @throws IllegalStateException if Feature Flags is disabled or misconfigured.
   */
  public static FeatureFlagsRuntime acquire(final AgentBridge bridge, final Settings settings) {
    synchronized (SHARED_LOCK) {
      if (shared == null) {
        final FeatureFlagsRuntime created = create(bridge, settings);
        created.start();
        created.registerShutdown(bridge);
        shared = created;
      }
      references++;
      return shared;
    }
  }

  /** Releases the shared runtime, stopping it on last release. */
  public static void release() {
    final FeatureFlagsRuntime stopped;
    synchronized (SHARED_LOCK) {
      if (references == 0 || --references > 0) {
        return;
      }
      stopped = shared;
      shared = null;
    }
    stopped.stop();
  }

  static FeatureFlagsRuntime create(final AgentBridge bridge, final Settings settings) {
    final String sourceName = settings.configurationSource();
    if (sourceName == null) {
      throw new IllegalStateException("Feature Flags is disabled");
    }
    final boolean remoteConfig = CONFIGURATION_SOURCE_REMOTE_CONFIG.equals(sourceName);
    final RuntimeServices services = new RuntimeServices(bridge.healthMetrics());
    final ConfigurationService source;
    final EventTransport transport;
    if (remoteConfig) {
      // TODO Support Remote Configuration without the Datadog Java agent.
      final ConfigurationSource remoteConfiguration;
      try {
        remoteConfiguration = bridge.remoteConfiguration();
      } catch (final LinkageError e) {
        throw new RemoteConfigurationUnavailableException(e);
      }
      if (remoteConfiguration == null) {
        throw new RemoteConfigurationUnavailableException(null);
      }
      source = new RemoteConfigurationService(remoteConfiguration);
      // Remote Configuration never falls back to direct intake.
      transport = bridge.eventProxy();
    } else {
      source = new CdnConfigurationSource(settings, services);
      final EventTransport proxy = bridge.eventProxy();
      final EventTransport direct = DirectIntakeTransport.create(settings);
      transport =
          proxy == null ? direct : direct == null ? proxy : new FallbackTransport(proxy, direct);
    }
    if (transport == null) {
      LOGGER.warn(
          "Feature Flags event delivery is disabled: set DD_API_KEY or attach the Datadog Java agent");
      return new FeatureFlagsRuntime(source, null, null);
    }
    final Map<String, String> context = eventContext(settings);
    final ExposurePipeline exposures =
        settings.isExposuresDatadogLoggingEnabled()
            ? new ExposurePipeline(
                ExposurePipeline.DEFAULT_CAPACITY,
                ExposurePipeline.DEFAULT_FLUSH_INTERVAL_IN_SECONDS,
                TimeUnit.SECONDS,
                transport,
                context,
                services)
            : null;
    final FlagEvaluationPipeline evaluations =
        settings.isEvaluationCountsEnabled()
            ? new FlagEvaluationPipeline(
                FlagEvaluationPipeline.DEFAULT_CAPACITY,
                FlagEvaluationPipeline.FLUSH_INTERVAL_SECONDS,
                TimeUnit.SECONDS,
                transport,
                context,
                services)
            : null;
    return new FeatureFlagsRuntime(source, exposures, evaluations);
  }

  /** Signals an unavailable RC connector before any runtime resources have been started. */
  public static final class RemoteConfigurationUnavailableException extends IllegalStateException {
    private RemoteConfigurationUnavailableException(@Nullable final LinkageError cause) {
      super(
          "Feature Flags Remote Configuration requires a compatible Datadog Java agent with"
              + " Remote Configuration enabled; attach a compatible agent or configure"
              + " DD_FEATURE_FLAGS_CONFIGURATION_SOURCE=agentless for CDN delivery",
          cause);
    }
  }

  private static Map<String, String> eventContext(final Settings settings) {
    final Map<String, String> context = new HashMap<>();
    putIfNotNull(context, "service", settings.service());
    putIfNotNull(context, "env", settings.env());
    putIfNotNull(context, "version", settings.version());
    return context;
  }

  private static void putIfNotNull(
      final Map<String, String> map, final String key, @Nullable final String value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  void start() {
    try {
      if (this.exposures != null) {
        this.exposures.start();
      }
      if (this.evaluations != null) {
        this.evaluations.start();
      }
      this.source.start(this::onConfiguration);
    } catch (final RuntimeException | Error e) {
      stop();
      throw e;
    }
  }

  /**
   * Stops the runtime at application shutdown: through the agent when it supports it, otherwise
   * through a JVM shutdown hook.
   *
   * @param bridge the bridge to the Datadog Java agent.
   */
  void registerShutdown(final AgentBridge bridge) {
    AutoCloseable registration = null;
    try {
      final ShutdownSource shutdown = bridge.shutdown();
      if (shutdown != null) {
        registration = shutdown.subscribe(this::stop);
      }
    } catch (final RuntimeException | LinkageError e) {
      LOGGER.debug("Could not subscribe to the agent shutdown, using a JVM shutdown hook", e);
    }
    this.shutdownRegistration = registration == null ? addShutdownHook() : registration;
  }

  @Nullable
  private AutoCloseable addShutdownHook() {
    final Thread hook = new Thread(this::stop, "dd-openfeature-shutdown");
    try {
      Runtime.getRuntime().addShutdownHook(hook);
    } catch (final IllegalStateException | SecurityException e) {
      LOGGER.debug("Could not register the Feature Flags shutdown hook", e);
      return null;
    }
    return () -> {
      if (Thread.currentThread() != hook) {
        Runtime.getRuntime().removeShutdownHook(hook);
      }
    };
  }

  void stop() {
    if (!this.stopped.compareAndSet(false, true)) {
      return;
    }
    final AutoCloseable registration = this.shutdownRegistration;
    if (registration != null) {
      try {
        registration.close();
      } catch (final Exception e) {
        // The JVM is already shutting down, or the agent backend is gone.
      }
    }
    // Signal both pipelines before waiting for either, so they flush in parallel within one budget.
    final long deadline = EventPipeline.shutdownDeadline();
    if (this.evaluations != null) {
      this.evaluations.requestShutdown();
    }
    if (this.exposures != null) {
      this.exposures.requestShutdown();
    }
    this.source.close();
    if (this.evaluations != null) {
      this.evaluations.awaitTermination(deadline);
    }
    if (this.exposures != null) {
      this.exposures.awaitTermination(deadline);
    }
  }

  private void onConfiguration(@Nullable final ServerConfiguration configuration) {
    this.configuration = configuration;
    for (final Consumer<ServerConfiguration> listener : this.listeners) {
      try {
        listener.accept(configuration);
      } catch (final RuntimeException e) {
        LOGGER.warn("Feature Flags configuration listener failed", e);
      }
    }
  }

  /**
   * Adds a configuration listener, receiving the current configuration if any.
   *
   * @param listener the listener to add.
   */
  public void addConfigurationListener(final Consumer<ServerConfiguration> listener) {
    this.listeners.add(listener);
    final ServerConfiguration current = this.configuration;
    if (current != null) {
      listener.accept(current);
    }
  }

  /**
   * Removes a configuration listener.
   *
   * @param listener the listener to remove.
   */
  public void removeConfigurationListener(final Consumer<ServerConfiguration> listener) {
    this.listeners.remove(listener);
  }

  /**
   * Gets the cache deduplicating the exposures of all the providers sharing this runtime. It is
   * kept even when exposures are not sent, so exposure hooks get the same decisions.
   *
   * @return the exposure deduplication cache.
   */
  public ExposureDeduplicationCache exposureCache() {
    return this.exposureCache;
  }

  /**
   * Tells whether exposures are sent to Datadog.
   *
   * @return whether exposure delivery is enabled.
   */
  public boolean isExposureDeliveryEnabled() {
    return this.exposures != null;
  }

  /**
   * Records an exposure, if exposure delivery is enabled.
   *
   * @param exposure the exposure to record.
   */
  public void recordExposure(final ExposureEvent exposure) {
    if (this.exposures != null) {
      this.exposures.accept(exposure);
    }
  }

  /**
   * @return the flag evaluation pipeline, or {@code null} if evaluation counts are disabled.
   */
  @Nullable
  public FlagEvaluationPipeline evaluations() {
    return this.evaluations;
  }
}
