package com.datadog.openfeature.internal.bridge.v1;

/**
 * Resolves the {@link AgentBridge} to use. Kept apart from {@link AgentBridge}: the agent injects
 * bridge implementations when instrumenting {@link #detect()}, and they cannot be defined while
 * their {@link AgentBridge} interface is itself being defined.
 */
public final class AgentBridges {
  private AgentBridges() {}

  /**
   * Detects the bridge to use. The Datadog Java agent instruments this method to return its own
   * bridge.
   *
   * @return the bridge to use.
   */
  public static AgentBridge detect() {
    return AgentBridge.NONE;
  }
}
