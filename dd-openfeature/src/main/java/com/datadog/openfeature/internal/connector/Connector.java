package com.datadog.openfeature.internal.connector;

import javax.annotation.Nullable;

/** Components supplied by the Datadog Java agent when attached, or no-op ones otherwise. */
public interface Connector {
  /** The connector used when no Datadog Java agent is attached. */
  Connector NONE = new NoConnector();

  /**
   * Looks up a setting from the agent configuration.
   *
   * @param key the setting key, using the {@code dd.} system property notation without the prefix.
   * @return the setting value, or {@code null} to fall back to system properties and environment.
   */
  @Nullable
  String setting(String key);

  /**
   * @return the Remote Configuration source, or {@code null} if not available.
   */
  @Nullable
  ConfigurationSource remoteConfiguration();

  /**
   * @return the transport through the Datadog Agent event platform proxy, or {@code null} if not
   *     available.
   */
  @Nullable
  EventTransport eventProxy();

  /**
   * @return the health metrics sink.
   */
  HealthMetrics healthMetrics();

  /**
   * @return the span enricher.
   */
  SpanEnricher spanEnricher();
}
