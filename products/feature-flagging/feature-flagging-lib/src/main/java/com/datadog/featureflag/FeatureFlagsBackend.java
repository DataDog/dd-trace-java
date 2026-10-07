package com.datadog.featureflag;

import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_AGENTLESS;
import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.CONFIGURATION_SOURCE_REMOTE_CONFIG;

import datadog.communication.BackendApiFactory;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.trace.api.Config;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.telemetry.CoreMetricCollector;
import java.io.IOException;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/** The agent services exposed to the Feature Flags SDK instrumentation. */
public final class FeatureFlagsBackend implements FeatureFlaggingGateway.Backend, AutoCloseable {
  private static final String API_KEY = "api-key";
  private static final String SITE = "site";
  private static final String ENV = "env";
  private static final String SERVICE = "service";
  private static final String VERSION = "version";

  private final SharedCommunicationObjects sco;
  private final Config config;
  private final EventProxy eventProxy;
  private final SpanEnrichmentWriter spanEnrichmentWriter;
  private final CoreMetricCollector metrics;
  private RemoteConfigServiceImpl remoteConfig;

  public FeatureFlagsBackend(final SharedCommunicationObjects sco, final Config config) {
    this(
        sco,
        config,
        new EventProxy(
            new BackendApiFactory(config, sco),
            CONFIGURATION_SOURCE_AGENTLESS.equals(config.getFeatureFlaggingConfigurationSource())
                && config.getApiKey() != null),
        SpanEnrichmentWriter.getInstance(),
        CoreMetricCollector.getInstance());
  }

  FeatureFlagsBackend(
      final SharedCommunicationObjects sco,
      final Config config,
      final EventProxy eventProxy,
      final SpanEnrichmentWriter spanEnrichmentWriter,
      final CoreMetricCollector metrics) {
    this.sco = sco;
    this.config = config;
    this.eventProxy = eventProxy;
    this.spanEnrichmentWriter = spanEnrichmentWriter;
    this.metrics = metrics;
  }

  /** Registers the Remote Configuration product early when it is the configuration source. */
  public void start() {
    if (CONFIGURATION_SOURCE_REMOTE_CONFIG.equals(
            this.config.getFeatureFlaggingConfigurationSource())
        && isRemoteConfigAvailable()) {
      remoteConfig().start();
    }
  }

  @Nullable
  @Override
  public String setting(final String key) {
    // Settings with agent defaults or derived values resolve through the agent configuration.
    switch (key) {
      case API_KEY:
        return this.config.getApiKey();
      case SITE:
        return this.config.getSite();
      case ENV:
        return this.config.getEnv();
      case SERVICE:
        return this.config.getServiceName();
      case VERSION:
        return this.config.getVersion();
      default:
        return this.config.configProvider().getString(key);
    }
  }

  @Override
  public boolean isRemoteConfigAvailable() {
    return this.config.isRemoteConfigEnabled();
  }

  @Override
  public AutoCloseable subscribeRemoteConfig(final Consumer<byte[]> listener) {
    return remoteConfig().subscribe(listener);
  }

  private synchronized RemoteConfigServiceImpl remoteConfig() {
    if (this.remoteConfig == null) {
      final ConfigurationPoller poller = this.sco.configurationPoller(this.config);
      if (poller == null) {
        throw new IllegalStateException("Remote Configuration is not available");
      }
      this.remoteConfig = new RemoteConfigServiceImpl(poller);
    }
    return this.remoteConfig;
  }

  @Override
  public boolean isEventProxyAvailable() {
    return this.eventProxy.isAvailable();
  }

  @Override
  public boolean postEvent(final String route, final byte[] json) throws IOException {
    return this.eventProxy.post(route, json);
  }

  @Override
  public void count(final String metric, final long value, @Nullable final String reason) {
    this.metrics.count(metric, value, reason == null ? null : "reason:" + reason);
  }

  @Override
  public void enrichSerialId(
      final int serialId, final boolean doLog, @Nullable final String targetingKey) {
    this.spanEnrichmentWriter.serialId(serialId, doLog, targetingKey);
  }

  @Override
  public void enrichRuntimeDefault(final String flagKey, @Nullable final Object defaultValue) {
    this.spanEnrichmentWriter.runtimeDefault(flagKey, defaultValue);
  }

  @Override
  public synchronized void close() {
    if (this.remoteConfig != null) {
      this.remoteConfig.close();
      this.remoteConfig = null;
    }
    this.spanEnrichmentWriter.close();
  }
}
