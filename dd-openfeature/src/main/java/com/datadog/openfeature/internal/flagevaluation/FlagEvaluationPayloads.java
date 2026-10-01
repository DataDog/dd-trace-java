package com.datadog.openfeature.internal.flagevaluation;

import static com.datadog.openfeature.internal.JsonWriting.writeKeyObject;
import static com.datadog.openfeature.internal.JsonWriting.writeValue;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.datadog.openfeature.internal.JsonWriting;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class FlagEvaluationPayloads {

  private static final byte[] PAYLOAD_SUFFIX = "]}".getBytes(UTF_8);
  private static final byte[] JSON_COMMA = ",".getBytes(UTF_8);

  /**
   * Wire prefix identifying a privacy-preserving, hashed targeting key. Emitted for full-tier rows
   * when {@code observeFullEvaluationData} is off. The suffix is the lower-case hex SHA-256 of the
   * UTF-8 targeting key (see {@link #hashTargetingKey}). This is a cross-SDK wire contract - keep
   * it in sync with the other server SDKs and the UFC/EVP spec.
   */
  private static final String HASHED_TARGETING_KEY_PREFIX = "sha256_";

  // Per-thread SHA-256 instance as hashing runs for every full-tier row without consent.
  private static final ThreadLocal<MessageDigest> SHA_256 =
      ThreadLocal.withInitial(
          () -> {
            try {
              return MessageDigest.getInstance("SHA-256");
            } catch (final NoSuchAlgorithmException e) {
              throw new IllegalStateException("SHA-256 not available", e);
            }
          });

  private FlagEvaluationPayloads() {}

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
    final byte[] contextJson = JsonWriting.write(generator -> writeValue(generator, context));
    return ("{\"context\":" + new String(contextJson, UTF_8) + ",\"flagEvaluations\":[")
        .getBytes(UTF_8);
  }

  static byte[] encodeEvent(final FlagEvaluationEvent event) {
    return JsonWriting.write(
        generator -> {
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
          generator.writeEndObject();
        });
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
    final StringBuilder hex = new StringBuilder(hash.length * 2);
    for (final byte b : hash) {
      final int v = b & 0xFF;
      if (v < 0x10) {
        hex.append('0');
      }
      hex.append(Integer.toHexString(v));
    }
    return hex.toString();
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
        final boolean observeFullEvaluationData,
        final long flushTimeMs) {
      final boolean includeRawContext = isFullTier && observeFullEvaluationData;
      return new FlagEvaluationEvent(
          flushTimeMs,
          bucket.flagKey,
          bucket.firstEvalMs,
          bucket.lastEvalMs,
          bucket.count,
          bucket.variant,
          bucket.allocationKey,
          resolveTargetingKey(bucket.targetingKey, isFullTier, observeFullEvaluationData),
          bucket.runtimeDefaultUsed,
          bucket.errorMessage,
          includeRawContext ? bucket.prunedAttrs : null);
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
