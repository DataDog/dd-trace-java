package com.datadog.featureflag;

import static datadog.trace.util.AgentThreadFactory.AgentThread.FEATURE_FLAG_SHUTDOWN;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts the agent Feature Flags backend used by the {@code dd-openfeature} SDK instrumentation.
 * Apart from registering the Remote Configuration product, the backend does no work until the SDK
 * uses it.
 */
public class FeatureFlaggingSystem {
  private static final Logger LOGGER = LoggerFactory.getLogger(FeatureFlaggingSystem.class);

  private static volatile FeatureFlagsBackend BACKEND;
  private static volatile Thread SHUTDOWN_HOOK;

  private FeatureFlaggingSystem() {}

  @SuppressFBWarnings(
      value = "USO_UNSAFE_STATIC_METHOD_SYNCHRONIZATION",
      justification =
          "Agent-internal class; Class object does not escape to app code and lock only guards the subsystem lifecycle.")
  public static synchronized void start(final SharedCommunicationObjects sco) {
    if (BACKEND != null) {
      LOGGER.debug("Feature Flagging system already started");
      return;
    }
    final Config config = Config.get();
    if (!config.isFeatureFlaggingProviderEnabled()) {
      LOGGER.debug("Feature Flagging system disabled");
      return;
    }
    // Tracing owns the shutdown path running before the JVM shutdown hooks. Without it, stop from a
    // shutdown hook once the SDK subscribes to the shutdown, so it still flushes its pending
    // events.
    final FeatureFlagsBackend backend =
        new FeatureFlagsBackend(
            sco, config, config.isTraceEnabled() ? null : FeatureFlaggingSystem::addShutdownHook);
    try {
      backend.start();
    } catch (final RuntimeException | Error e) {
      backend.close();
      throw e;
    }
    BACKEND = backend;
    FeatureFlaggingGateway.register(backend);
    LOGGER.debug("Feature Flagging system started");
  }

  @SuppressFBWarnings(
      value = "USO_UNSAFE_STATIC_METHOD_SYNCHRONIZATION",
      justification =
          "Agent-internal class; Class object does not escape to app code and lock only guards the subsystem lifecycle.")
  public static synchronized void stop() {
    final FeatureFlagsBackend backend = BACKEND;
    BACKEND = null;
    final Thread shutdownHook = SHUTDOWN_HOOK;
    SHUTDOWN_HOOK = null;
    if (shutdownHook != null && shutdownHook != Thread.currentThread()) {
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      } catch (final IllegalStateException ignored) {
        // The JVM is already shutting down; the hook finds the system stopped.
      }
    }
    FeatureFlaggingGateway.register(null);
    if (backend != null) {
      backend.close();
    }
    LOGGER.debug("Feature Flagging system stopped");
  }

  @SuppressFBWarnings(
      value = "USO_UNSAFE_STATIC_METHOD_SYNCHRONIZATION",
      justification =
          "Agent-internal class; Class object does not escape to app code and lock only guards the subsystem lifecycle.")
  private static synchronized void addShutdownHook() {
    if (BACKEND == null || SHUTDOWN_HOOK != null) {
      return;
    }
    final Thread hook = newAgentThread(FEATURE_FLAG_SHUTDOWN, FeatureFlaggingSystem::stop);
    Runtime.getRuntime().addShutdownHook(hook);
    SHUTDOWN_HOOK = hook;
  }

  static boolean isShutdownHookRegistered() {
    return SHUTDOWN_HOOK != null;
  }
}
