package com.datadog.featureflag;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.flagevaluation.FlagEvaluationWriter;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared resource lifecycle. Assemblies select transports and the runtime's ownership scope. */
public final class ProviderRuntime implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(ProviderRuntime.class);

  private final ConfigurationSourceService source;
  private final ExposureWriter exposures;
  private FlagEvaluationWriter evaluations;
  private final Object closeLock = new Object();
  private boolean closed;

  private ProviderRuntime(ConfigurationSourceService source, ExposureWriter exposures) {
    this.source = source;
    this.exposures = exposures;
  }

  public static ProviderRuntime start(
      ConfigurationSourceService source,
      ExposureWriter exposures,
      Supplier<FlagEvaluationWriter> evaluationFactory,
      boolean evaluationCountsEnabled) {
    ProviderRuntime runtime = new ProviderRuntime(source, exposures);
    try {
      if (source != null) {
        source.init();
      }
      exposures.init();
      FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(evaluationCountsEnabled);
      if (evaluationCountsEnabled) {
        // Retain the writer before start() so rollback also closes a partially started writer.
        runtime.evaluations = evaluationFactory.get();
        runtime.evaluations.start();
      } else {
        FeatureFlaggingGateway.setFlagEvalWriter(null);
      }
      return runtime;
    } catch (RuntimeException | Error failure) {
      runtime.close();
      throw failure;
    }
  }

  public boolean hasConfigurationSource() {
    return source != null;
  }

  @Override
  public void close() {
    synchronized (closeLock) {
      if (closed) {
        return;
      }
      closed = true;
      FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(false);
      FeatureFlaggingGateway.setFlagEvalWriter(null);
      closeQuietly(evaluations);
      closeQuietly(exposures);
      closeQuietly(source);
    }
  }

  private static void closeQuietly(AutoCloseable resource) {
    if (resource != null) {
      try {
        resource.close();
      } catch (Exception failure) {
        LOGGER.debug("Failed to close Feature Flags runtime resource", failure);
      }
    }
  }
}
