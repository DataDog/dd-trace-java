package com.datadog.featureflag;

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
    final FeatureFlagsBackend backend = new FeatureFlagsBackend(sco, config);
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
    FeatureFlaggingGateway.register(null);
    if (backend != null) {
      backend.close();
    }
    LOGGER.debug("Feature Flagging system stopped");
  }
}
