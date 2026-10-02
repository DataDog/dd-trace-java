package com.datadog.openfeature.internal.connector;

/** Resolves the {@link Connector} to use. */
public final class Connectors {
  private Connectors() {}

  /**
   * Detects the connector to use. The Datadog Java agent instruments this method to return its own
   * connector.
   *
   * @return the connector to use.
   */
  public static Connector detect() {
    return Connector.NONE;
  }
}
