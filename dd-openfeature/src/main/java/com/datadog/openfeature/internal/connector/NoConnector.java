package com.datadog.openfeature.internal.connector;

import javax.annotation.Nullable;

final class NoConnector implements Connector {
  @Nullable
  @Override
  public String setting(final String key) {
    return null;
  }

  @Nullable
  @Override
  public ConfigurationSource remoteConfiguration() {
    return null;
  }

  @Nullable
  @Override
  public EventTransport eventProxy() {
    return null;
  }

  @Override
  public HealthMetrics healthMetrics() {
    return HealthMetrics.NOOP;
  }

  @Override
  public SpanEnricher spanEnricher() {
    return SpanEnricher.NOOP;
  }
}
