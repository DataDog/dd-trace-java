package com.datadog.featureflag;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class FlagEvaluationPayloads {

  private static final byte[] PAYLOAD_SUFFIX = FeatureFlagEvpPublisher.utf8Bytes("]}");
  private static final byte[] JSON_COMMA = FeatureFlagEvpPublisher.utf8Bytes(",");
  private static final JsonAdapter<FlagEvaluationEvent> EVENT_JSON_ADAPTER;
  private static final JsonAdapter<Map<String, String>> CONTEXT_JSON_ADAPTER;

  static {
    final Moshi moshi = new Moshi.Builder().build();
    EVENT_JSON_ADAPTER = moshi.adapter(FlagEvaluationEvent.class);
    final Type contextType = Types.newParameterizedType(Map.class, String.class, String.class);
    CONTEXT_JSON_ADAPTER = moshi.adapter(contextType);
  }

  private FlagEvaluationPayloads() {}

  static class FlagEvaluationsRequest {
    public final Map<String, String> context;
    public final List<FlagEvaluationEvent> flagEvaluations;

    FlagEvaluationsRequest(
        final Map<String, String> context, final List<FlagEvaluationEvent> flagEvaluations) {
      this.context = context;
      this.flagEvaluations = flagEvaluations;
    }
  }

  static EncodedPayloads buildPayloads(
      final List<FlagEvaluationEvent> events,
      final Map<String, String> context,
      final int payloadSizeLimitBytes) {
    final byte[] prefix = payloadPrefix(context);
    EncodedPayloadBuilder current = new EncodedPayloadBuilder(prefix);
    final List<byte[]> payloads = new ArrayList<>();
    long droppedPayloadLimit = 0;
    long degradedPayloadLimit = 0;

    for (final FlagEvaluationEvent event : events) {
      byte[] eventBytes = encodeEvent(event);

      if (!current.canAdd(eventBytes, payloadSizeLimitBytes) && !current.isEmpty()) {
        payloads.add(current.toByteArray());
        current = new EncodedPayloadBuilder(prefix);
      }

      if (current.canAdd(eventBytes, payloadSizeLimitBytes)) {
        current.add(eventBytes);
        continue;
      }

      final FlagEvaluationEvent degraded = event.withoutTargetingKeyAndContext();
      if (degraded != null) {
        eventBytes = encodeEvent(degraded);
        if (!current.canAdd(eventBytes, payloadSizeLimitBytes) && !current.isEmpty()) {
          payloads.add(current.toByteArray());
          current = new EncodedPayloadBuilder(prefix);
        }
        if (current.canAdd(eventBytes, payloadSizeLimitBytes)) {
          current.add(eventBytes);
          degradedPayloadLimit += event.evaluation_count;
          continue;
        }
      }

      droppedPayloadLimit += event.evaluation_count;
    }

    if (!current.isEmpty()) {
      payloads.add(current.toByteArray());
    }
    return new EncodedPayloads(payloads, droppedPayloadLimit, degradedPayloadLimit);
  }

  private static byte[] payloadPrefix(final Map<String, String> context) {
    return FeatureFlagEvpPublisher.utf8Bytes(
        "{\"context\":" + CONTEXT_JSON_ADAPTER.toJson(context) + ",\"flagEvaluations\":[");
  }

  private static byte[] encodeEvent(final FlagEvaluationEvent event) {
    return FeatureFlagEvpPublisher.utf8Bytes(EVENT_JSON_ADAPTER.toJson(event));
  }

  static final class EncodedPayloads {
    final List<byte[]> bodies;
    final long droppedPayloadLimit;
    final long degradedPayloadLimit;

    private EncodedPayloads(
        final List<byte[]> bodies,
        final long droppedPayloadLimit,
        final long degradedPayloadLimit) {
      this.bodies = bodies;
      this.droppedPayloadLimit = droppedPayloadLimit;
      this.degradedPayloadLimit = degradedPayloadLimit;
    }
  }

  private static final class EncodedPayloadBuilder {
    private final byte[] prefix;
    private final List<byte[]> events = new ArrayList<>();
    private int eventBytes;

    private EncodedPayloadBuilder(final byte[] prefix) {
      this.prefix = prefix;
    }

    private boolean isEmpty() {
      return events.isEmpty();
    }

    private boolean canAdd(final byte[] event, final int payloadSizeLimitBytes) {
      return sizeWith(event) <= payloadSizeLimitBytes;
    }

    private int sizeWith(final byte[] event) {
      return prefix.length + PAYLOAD_SUFFIX.length + eventBytes + event.length + events.size();
    }

    private void add(final byte[] event) {
      events.add(event);
      eventBytes += event.length;
    }

    private byte[] toByteArray() {
      final int size = prefix.length + PAYLOAD_SUFFIX.length + eventBytes;
      final ByteArrayOutputStream out = new ByteArrayOutputStream(size + events.size());
      out.write(prefix, 0, prefix.length);
      for (int i = 0; i < events.size(); i++) {
        if (i > 0) {
          out.write(JSON_COMMA, 0, JSON_COMMA.length);
        }
        final byte[] event = events.get(i);
        out.write(event, 0, event.length);
      }
      out.write(PAYLOAD_SUFFIX, 0, PAYLOAD_SUFFIX.length);
      return out.toByteArray();
    }
  }

  static class FlagEvaluationEvent {
    public final long timestamp;
    public final FlagKeyObject flag;
    public final long first_evaluation;
    public final long last_evaluation;
    public final long evaluation_count;
    public final KeyObject variant;
    public final KeyObject allocation;
    public final String targeting_key;
    public final Boolean runtime_default_used;
    public final EventContext context;
    public final ErrorObject error;

    FlagEvaluationEvent(
        final long timestamp,
        final String flagKey,
        final long firstEvalMs,
        final long lastEvalMs,
        final long count,
        final String variant,
        final String allocation,
        final String targetingKey,
        final boolean runtimeDefaultUsed,
        final String errorMessage,
        final Map<String, Object> evaluationAttrs) {
      this.timestamp = timestamp;
      this.flag = new FlagKeyObject(flagKey);
      this.first_evaluation = firstEvalMs;
      this.last_evaluation = lastEvalMs;
      this.evaluation_count = count;
      this.variant = (variant != null && !variant.isEmpty()) ? new KeyObject(variant) : null;
      this.allocation =
          (allocation != null && !allocation.isEmpty()) ? new KeyObject(allocation) : null;
      this.targeting_key = targetingKey;
      this.runtime_default_used = runtimeDefaultUsed ? Boolean.TRUE : null;
      this.context =
          (evaluationAttrs != null && !evaluationAttrs.isEmpty())
              ? new EventContext(evaluationAttrs)
              : null;
      this.error =
          (errorMessage != null && !errorMessage.isEmpty()) ? new ErrorObject(errorMessage) : null;
    }

    static FlagEvaluationEvent fromBucket(
        final FlagEvaluationAggregator.EvalBucket bucket,
        final boolean isFullTier,
        final long flushTimeMs) {
      return new FlagEvaluationEvent(
          flushTimeMs,
          bucket.flagKey,
          bucket.firstEvalMs,
          bucket.lastEvalMs,
          bucket.count,
          bucket.variant,
          bucket.allocationKey,
          isFullTier ? bucket.targetingKey : null,
          bucket.runtimeDefaultUsed,
          bucket.errorMessage,
          isFullTier ? bucket.prunedAttrs : null);
    }

    FlagEvaluationEvent withoutTargetingKeyAndContext() {
      if (targeting_key == null && context == null) {
        return null;
      }
      return new FlagEvaluationEvent(
          timestamp,
          flag.key,
          first_evaluation,
          last_evaluation,
          evaluation_count,
          keyOf(variant),
          keyOf(allocation),
          null,
          Boolean.TRUE.equals(runtime_default_used),
          messageOf(error),
          null);
    }
  }

  private static String keyOf(final KeyObject object) {
    return object == null ? null : object.key;
  }

  private static String messageOf(final ErrorObject object) {
    return object == null ? null : object.message;
  }

  static class KeyObject {
    public final String key;

    KeyObject(final String key) {
      this.key = key;
    }
  }

  static class FlagKeyObject {
    public final String key;

    FlagKeyObject(final String key) {
      this.key = key;
    }
  }

  static class ErrorObject {
    public final String message;

    ErrorObject(final String message) {
      this.message = message;
    }
  }

  static class EventContext {
    public final Map<String, Object> evaluation;

    EventContext(final Map<String, Object> evaluation) {
      this.evaluation = evaluation;
    }
  }
}
