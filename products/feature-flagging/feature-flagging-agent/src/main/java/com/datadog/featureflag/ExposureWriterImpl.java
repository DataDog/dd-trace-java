package com.datadog.featureflag;

import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import java.util.concurrent.TimeUnit;

/** Transport composition for the shared exposure pipeline. */
public class ExposureWriterImpl extends ExposurePipeline {
  public ExposureWriterImpl(final SharedCommunicationObjects sco, final Config config) {
    this(1 << 16, 1, SECONDS, sco, config);
  }

  ExposureWriterImpl(
      final SharedCommunicationObjects sco, final Config config, final boolean agentProxyEnabled) {
    this(
        1 << 16,
        1,
        SECONDS,
        new FeatureFlagBackendApiFactory(
            config, sco, FeatureFlagEventType.EXPOSURE, agentProxyEnabled),
        config);
  }

  ExposureWriterImpl(
      final int capacity,
      final long flushInterval,
      final TimeUnit timeUnit,
      final SharedCommunicationObjects sco,
      final Config config) {
    this(
        capacity,
        flushInterval,
        timeUnit,
        new FeatureFlagBackendApiFactory(config, sco, FeatureFlagEventType.EXPOSURE),
        config);
  }

  ExposureWriterImpl(
      final int capacity,
      final long flushInterval,
      final TimeUnit timeUnit,
      final FeatureFlagBackendApiFactory backendApiFactory,
      final Config config) {
    super(
        capacity,
        flushInterval,
        timeUnit,
        BackendEventTransport.adapt(backendApiFactory::create),
        FeatureFlagEvpContext.from(config),
        AgentRuntimeServices.INSTANCE);
  }
}
