package com.datadog.featureflag;

import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.common.queue.MessagePassingBlockingQueue;
import datadog.communication.BackendApi;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.featureflag.flagevaluation.FlagEvalEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Transport composition for the shared aggregation pipeline. */
public class FlagEvaluationWriterImpl extends FlagEvaluationPipeline {
  public FlagEvaluationWriterImpl(final SharedCommunicationObjects sco, final Config config) {
    this(
        DEFAULT_CAPACITY,
        FLUSH_INTERVAL_SECONDS,
        SECONDS,
        new FeatureFlagBackendApiFactory(config, sco, FeatureFlagEventType.FLAG_EVALUATION)::create,
        config);
  }

  FlagEvaluationWriterImpl(
      final SharedCommunicationObjects sco, final Config config, final boolean agentProxyEnabled) {
    this(
        DEFAULT_CAPACITY,
        FLUSH_INTERVAL_SECONDS,
        SECONDS,
        new FeatureFlagBackendApiFactory(
                config, sco, FeatureFlagEventType.FLAG_EVALUATION, agentProxyEnabled)
            ::create,
        config);
  }

  FlagEvaluationWriterImpl(
      final int capacity,
      final long flushInterval,
      final TimeUnit timeUnit,
      final Supplier<BackendApi> backendApiSupplier,
      final Config config) {
    super(
        capacity,
        flushInterval,
        timeUnit,
        BackendEventTransport.adapt(backendApiSupplier),
        FeatureFlagEvpContext.from(config),
        AgentRuntimeServices.INSTANCE);
  }

  static class FlagEvaluationSerializingHandler
      extends FlagEvaluationPipeline.FlagEvaluationSerializingHandler {
    FlagEvaluationSerializingHandler(
        Supplier<BackendApi> transport,
        MessagePassingBlockingQueue<FlagEvalEvent> queue,
        long flush,
        TimeUnit unit,
        Map<String, String> context,
        AtomicLong drops,
        ConcurrentHashMap<String, AtomicLong> truncations,
        Runnable onError,
        int limit) {
      super(
          BackendEventTransport.adapt(transport),
          queue,
          flush,
          unit,
          context,
          drops,
          truncations,
          onError,
          limit,
          AgentRuntimeServices.INSTANCE);
    }
  }

  static class SerializingHandlerForTest extends FlagEvaluationPipeline.SerializingHandlerForTest {
    SerializingHandlerForTest(
        Supplier<BackendApi> transport, Map<String, String> context, int limit) {
      super(BackendEventTransport.adapt(transport), context, limit, AgentRuntimeServices.INSTANCE);
    }
  }

  static SerializingHandlerForTest createHandlerForTest(
      Supplier<BackendApi> transport, Map<String, String> context) {
    return createHandlerForTest(transport, context, FLAG_EVALUATION_PAYLOAD_SIZE_LIMIT_BYTES);
  }

  static SerializingHandlerForTest createHandlerForTest(
      Supplier<BackendApi> transport, Map<String, String> context, int limit) {
    return new SerializingHandlerForTest(transport, context, limit);
  }
}
