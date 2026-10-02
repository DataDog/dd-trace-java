package com.datadog.openfeature.internal;

import static com.datadog.openfeature.internal.config.Settings.CONFIGURATION_SOURCE_REMOTE_CONFIG;

import com.datadog.openfeature.internal.config.Settings;
import com.datadog.openfeature.internal.connector.ConfigurationSource;
import com.datadog.openfeature.internal.connector.Connector;
import com.datadog.openfeature.internal.connector.EventTransport;
import com.datadog.openfeature.internal.delivery.CdnConfigurationSource;
import com.datadog.openfeature.internal.delivery.DirectIntakeTransport;
import com.datadog.openfeature.internal.delivery.FallbackTransport;
import com.datadog.openfeature.internal.delivery.RemoteConfigurationService;
import com.datadog.openfeature.internal.exposure.ExposureEvent;
import com.datadog.openfeature.internal.exposure.ExposurePipeline;
import com.datadog.openfeature.internal.flagevaluation.FlagEvaluationPipeline;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Feature Flags runtime: configuration delivery and event pipelines. A single runtime is shared
 * by all the providers of a class loader, and stopped when the last one releases it.
 */
public final class FeatureFlagsRuntime {
  private static final Logger LOGGER = LoggerFactory.getLogger(FeatureFlagsRuntime.class);

  private static final Object SHARED_LOCK = new Object();
  private static FeatureFlagsRuntime shared;
  private static int references;

  private final ConfigurationService source;
  @Nullable private final ExposurePipeline exposures;
  @Nullable private final FlagEvaluationPipeline evaluations;
  private final List<Consumer<ServerConfiguration>> listeners = new CopyOnWriteArrayList<>();
  private volatile ServerConfiguration configuration;

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
   * @param connector the connector to the Datadog Java agent.
   * @param settings the SDK settings.
   * @return the shared runtime.
   * @throws IllegalStateException if Feature Flags is disabled or misconfigured.
   */
  public static FeatureFlagsRuntime acquire(final Connector connector, final Settings settings) {
    synchronized (SHARED_LOCK) {
      if (shared == null) {
        final FeatureFlagsRuntime created = create(connector, settings);
        created.start();
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

  static FeatureFlagsRuntime create(final Connector connector, final Settings settings) {
    final String sourceName = settings.configurationSource();
    if (sourceName == null) {
      throw new IllegalStateException("Feature Flags is disabled");
    }
    final boolean remoteConfig = CONFIGURATION_SOURCE_REMOTE_CONFIG.equals(sourceName);
    final RuntimeServices services = new RuntimeServices(connector.healthMetrics());
    final ConfigurationService source;
    final EventTransport transport;
    if (remoteConfig) {
      // TODO Support Remote Configuration without the Datadog Java agent.
      final ConfigurationSource remoteConfiguration = connector.remoteConfiguration();
      if (remoteConfiguration == null) {
        throw new IllegalStateException(
            "Feature Flags Remote Configuration source requires the Datadog Java agent with"
                + " Remote Configuration enabled");
      }
      source = new RemoteConfigurationService(remoteConfiguration);
      // Remote Configuration never falls back to direct intake.
      transport = connector.eventProxy();
    } else {
      source = new CdnConfigurationSource(settings, services);
      final EventTransport proxy = connector.eventProxy();
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
        new ExposurePipeline(
            ExposurePipeline.DEFAULT_CAPACITY,
            ExposurePipeline.DEFAULT_FLUSH_INTERVAL_IN_SECONDS,
            TimeUnit.SECONDS,
            transport,
            context,
            services);
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

  void stop() {
    this.source.close();
    if (this.evaluations != null) {
      this.evaluations.close();
    }
    if (this.exposures != null) {
      this.exposures.close();
    }
  }

  private void onConfiguration(@Nullable final ServerConfiguration configuration) {
    this.configuration = configuration;
    for (final Consumer<ServerConfiguration> listener : this.listeners) {
      listener.accept(configuration);
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
