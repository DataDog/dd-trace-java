package com.datadog.openfeature.internal.flagevaluation;

import static com.datadog.openfeature.internal.JsonWriting.writeKeyObject;
import static com.datadog.openfeature.internal.JsonWriting.writeValue;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.datadog.openfeature.internal.JsonArrayPayload;
import com.datadog.openfeature.internal.JsonWriting;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

final class FlagEvaluationPayloads {

  private static final byte[] PAYLOAD_SUFFIX = "]}".getBytes(UTF_8);

  /**
   * Wire prefix identifying a privacy-preserving, hashed targeting key. Emitted for full-tier rows
   * when {@code observeFullEvaluationData} is off. The suffix is the lower-case hex SHA-256 of the
   * UTF-8 targeting key (see {@link #hashTargetingKey}). This is a cross-SDK wire contract - keep
   * it in sync with the other server SDKs and the UFC/EVP spec.
   */
  private static final String HASHED_TARGETING_KEY_PREFIX = "sha256_";

  private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

  // Per-thread SHA-256 instance as hashing runs for every full-tier row without consent.
  private static final ThreadLocal<MessageDigest> SHA_256 =
      ThreadLocal.withInitial(() -> {
        try {
          return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
          throw new IllegalStateException("SHA-256 not available", e);
        }
      });

  private FlagEvaluationPayloads() {}

  /** Receives each payload as it closes. */
  interface PayloadSink {
    /**
     * @param payload the chunks of the payload's JSON document, valid only until this returns: the
     *     next payload reuses them.
     * @throws IOException to stop writing payloads.
     */
    void accept(List<ByteBuffer> payload) throws IOException;
  }

  /** What writing the payloads of a flush counted: kept when a payload fails to post. */
  static final class PayloadCounts {
    /** The payloads the sink accepted. */
    int payloads;

    long droppedPayloadLimit;
    long degradedPayloadLimit;
  }

  /**
   * Splits events into payloads under the size limit and hands each to the sink as it closes, so a
   * flush holds one payload at a time however many it takes, rather than all of them at once.
   *
   * @throws IOException if the sink fails; the remaining events are not written.
   */
  static void writePayloads(
      final List<FlagEvaluationEvent> events,
      final Map<String, String> context,
      final int payloadSizeLimitBytes,
      final PayloadCounts counts,
      final PayloadSink sink)
      throws IOException {
    final Payloads payloads =
        new Payloads(payloadPrefix(context), payloadSizeLimitBytes, counts, sink);
    for (final FlagEvaluationEvent event : events) {
      if (payloads.add(event)) {
        continue;
      }
      final FlagEvaluationEvent degraded = event.withoutTargetingKeyAndContext();
      if (degraded != null && payloads.add(degraded)) {
        counts.degradedPayloadLimit += event.evaluation_count;
        continue;
      }
      counts.droppedPayloadLimit += event.evaluation_count;
    }
    payloads.close();
  }

  private static byte[] payloadPrefix(final Map<String, String> context) {
    final byte[] contextJson = JsonWriting.write(generator -> writeValue(generator, context));
    return ("{\"context\":" + new String(contextJson, UTF_8) + ",\"flagEvaluations\":[")
        .getBytes(UTF_8);
  }

  static byte[] encodeEvent(final FlagEvaluationEvent event) {
    return JsonWriting.write(generator -> writeEvent(generator, event));
  }

  private static void writeEvent(final JsonGenerator generator, final FlagEvaluationEvent event)
      throws IOException {
    generator.writeStartObject();
    generator.writeNumberField("timestamp", event.timestamp);
    writeKeyObject(generator, "flag", event.flag.key);
    generator.writeNumberField("first_evaluation", event.first_evaluation);
    generator.writeNumberField("last_evaluation", event.last_evaluation);
    generator.writeNumberField("evaluation_count", event.evaluation_count);
    writeKeyObject(generator, "variant", keyOf(event.variant));
    writeKeyObject(generator, "allocation", keyOf(event.allocation));
    if (event.targeting_key != null) {
      generator.writeStringField("targeting_key", event.targeting_key);
    }
    if (event.runtime_default_used != null) {
      generator.writeBooleanField("runtime_default_used", event.runtime_default_used);
    }
    if (event.context != null) {
      generator.writeObjectFieldStart("context");
      generator.writeFieldName("evaluation");
      writeValue(generator, event.context.evaluation);
      generator.writeEndObject();
    }
    if (event.error != null) {
      generator.writeObjectFieldStart("error");
      generator.writeStringField("message", event.error.message);
      generator.writeEndObject();
    }
    if (event.features != null) {
      generator.writeFieldName("features");
      writeValue(generator, event.features);
    }
    generator.writeEndObject();
  }

  /**
   * Hashes a targeting key.
   *
   * @param value the targeting key.
   * @return the lower-case hex SHA-256 of the UTF-8 targeting key.
   */
  static String hashTargetingKey(final String value) {
    final MessageDigest digest = SHA_256.get();
    digest.reset();
    final byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
    final char[] hex = new char[hash.length * 2];
    for (int i = 0; i < hash.length; i++) {
      hex[2 * i] = HEX_DIGITS[(hash[i] >> 4) & 0xF];
      hex[2 * i + 1] = HEX_DIGITS[hash[i] & 0xF];
    }
    return new String(hex);
  }

  /**
   * Splits events into payloads under the size limit, in one {@link JsonArrayPayload} that each
   * payload reuses once the sink accepted the previous one.
   */
  private static final class Payloads {
    private final PayloadCounts counts;
    private final PayloadSink sink;
    private final JsonArrayPayload current;

    Payloads(
        final byte[] prefix,
        final int payloadSizeLimitBytes,
        final PayloadCounts counts,
        final PayloadSink sink) {
      this.counts = counts;
      this.sink = sink;
      this.current = new JsonArrayPayload(prefix, PAYLOAD_SUFFIX, payloadSizeLimitBytes);
    }

    /**
     * @return whether the event was added, to the current payload or to a new one.
     */
    boolean add(final FlagEvaluationEvent event) throws IOException {
      if (current.append(generator -> writeEvent(generator, event))) {
        return true;
      }
      if (current.count() == 0) {
        return false;
      }
      closeCurrent();
      return current.append(generator -> writeEvent(generator, event));
    }

    private void closeCurrent() throws IOException {
      // Not reset when the sink fails: the payload is dropped with the flush, and a timed-out
      // request may still be reading its buffers.
      sink.accept(current.body());
      counts.payloads++;
      current.reset();
    }

    void close() throws IOException {
      if (current.count() > 0) {
        closeCurrent();
      }
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
    public final Map<String, Object> features;

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
        final Map<String, Object> evaluationAttrs,
        final Map<String, Object> features) {
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
      this.features = (features != null && !features.isEmpty()) ? features : null;
    }

    static FlagEvaluationEvent fromBucket(
        final FlagEvaluationAggregator.EvalBucket bucket,
        final boolean isFullTier,
        final boolean observeFullEvaluationData,
        final long flushTimeMs) {
      return of(
          flushTimeMs,
          bucket.flagKey,
          bucket.firstEvalMs,
          bucket.lastEvalMs,
          bucket.count,
          bucket.variant,
          bucket.allocationKey,
          bucket.targetingKey,
          bucket.runtimeDefaultUsed,
          bucket.errorMessage,
          bucket.prunedAttrs,
          bucket.features,
          isFullTier,
          observeFullEvaluationData);
    }

    /** PROTOTYPE: a row from a {@link ConcurrentFlagAggregator} bucket. */
    static FlagEvaluationEvent fromBucket(
        final ConcurrentFlagAggregator.Bucket bucket, final long flushTimeMs) {
      return of(
          flushTimeMs,
          bucket.flagKey,
          bucket.firstEvalMs,
          bucket.lastEvalMs,
          bucket.count,
          bucket.variant,
          bucket.allocationKey,
          bucket.targetingKey,
          bucket.runtimeDefaultUsed,
          bucket.errorMessage,
          bucket.prunedAttrs,
          bucket.features,
          !bucket.degraded,
          bucket.observeFullEvaluationData);
    }

    private static FlagEvaluationEvent of(
        final long flushTimeMs,
        final String flagKey,
        final long firstEvalMs,
        final long lastEvalMs,
        final long count,
        final String variant,
        final String allocationKey,
        final String targetingKey,
        final boolean runtimeDefaultUsed,
        final String errorMessage,
        final Map<String, Object> prunedAttrs,
        final Map<String, Object> features,
        final boolean isFullTier,
        final boolean observeFullEvaluationData) {
      final boolean includeRawContext = isFullTier && observeFullEvaluationData;
      return new FlagEvaluationEvent(
          flushTimeMs,
          flagKey,
          firstEvalMs,
          lastEvalMs,
          count,
          variant,
          allocationKey,
          resolveTargetingKey(targetingKey, isFullTier, observeFullEvaluationData),
          runtimeDefaultUsed,
          errorMessage,
          includeRawContext ? prunedAttrs : null,
          // Features come from the flag configuration, so both tiers keep them whatever the
          // consent.
          features);
    }

    private static String resolveTargetingKey(
        final String rawTargetingKey,
        final boolean isFullTier,
        final boolean observeFullEvaluationData) {
      if (!isFullTier || rawTargetingKey == null) {
        return null;
      }
      if (observeFullEvaluationData) {
        return rawTargetingKey;
      }
      return HASHED_TARGETING_KEY_PREFIX + hashTargetingKey(rawTargetingKey);
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
          null,
          features);
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
