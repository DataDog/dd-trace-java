package com.datadog.featureflag;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class ExposurePayloads {

  private static final byte[] PAYLOAD_SUFFIX = FeatureFlagEvpPublisher.utf8Bytes("]}");
  private static final byte[] JSON_COMMA = FeatureFlagEvpPublisher.utf8Bytes(",");

  private static final JsonAdapter<ExposureEvent> EVENT_JSON_ADAPTER;
  private static final JsonAdapter<Map<String, String>> CONTEXT_JSON_ADAPTER;

  static {
    final Moshi moshi = new Moshi.Builder().build();
    EVENT_JSON_ADAPTER = moshi.adapter(ExposureEvent.class);
    final Type contextType = Types.newParameterizedType(Map.class, String.class, String.class);
    CONTEXT_JSON_ADAPTER = moshi.adapter(contextType);
  }

  private ExposurePayloads() {}

  static EncodedPayloads buildPayloadsForTest(
      final List<ExposureEvent> events,
      final Map<String, String> context,
      final int payloadSizeLimitBytes) {
    final List<EncodedPayload> payloads = new ArrayList<>();
    final EncodingResult result =
        writePayloads(events, context, payloadSizeLimitBytes, payloads::add);
    return new EncodedPayloads(payloads, result.droppedPayloadLimit, result.droppedSerialization);
  }

  static EncodingResult writePayloads(
      final List<ExposureEvent> events,
      final Map<String, String> context,
      final int payloadSizeLimitBytes,
      final Consumer<EncodedPayload> payloadConsumer) {
    final byte[] prefix = payloadPrefix(context);
    EncodedPayloadBuilder current = new EncodedPayloadBuilder(prefix);
    long droppedPayloadLimit = 0;
    long droppedSerialization = 0;

    for (final ExposureEvent event : events) {
      final byte[] eventBytes;
      try {
        eventBytes = encodeEvent(event);
      } catch (final RuntimeException ignored) {
        droppedSerialization++;
        continue;
      }
      if (!current.canAdd(eventBytes, payloadSizeLimitBytes) && !current.isEmpty()) {
        payloadConsumer.accept(current.toPayload());
        current = new EncodedPayloadBuilder(prefix);
      }
      if (current.canAdd(eventBytes, payloadSizeLimitBytes)) {
        current.add(eventBytes);
      } else {
        droppedPayloadLimit++;
      }
    }

    if (!current.isEmpty()) {
      payloadConsumer.accept(current.toPayload());
    }
    return new EncodingResult(droppedPayloadLimit, droppedSerialization);
  }

  private static byte[] payloadPrefix(final Map<String, String> context) {
    return FeatureFlagEvpPublisher.utf8Bytes(
        "{\"context\":" + CONTEXT_JSON_ADAPTER.toJson(context) + ",\"exposures\":[");
  }

  private static byte[] encodeEvent(final ExposureEvent event) {
    return FeatureFlagEvpPublisher.utf8Bytes(EVENT_JSON_ADAPTER.toJson(event));
  }

  static final class EncodedPayloads {
    final List<EncodedPayload> payloads;
    final long droppedPayloadLimit;
    final long droppedSerialization;

    private EncodedPayloads(
        final List<EncodedPayload> payloads,
        final long droppedPayloadLimit,
        final long droppedSerialization) {
      this.payloads = payloads;
      this.droppedPayloadLimit = droppedPayloadLimit;
      this.droppedSerialization = droppedSerialization;
    }
  }

  static final class EncodingResult {
    final long droppedPayloadLimit;
    final long droppedSerialization;

    private EncodingResult(final long droppedPayloadLimit, final long droppedSerialization) {
      this.droppedPayloadLimit = droppedPayloadLimit;
      this.droppedSerialization = droppedSerialization;
    }
  }

  static final class EncodedPayload {
    final byte[] body;
    final int eventCount;

    private EncodedPayload(final byte[] body, final int eventCount) {
      this.body = body;
      this.eventCount = eventCount;
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

    private long sizeWith(final byte[] event) {
      return (long) prefix.length
          + PAYLOAD_SUFFIX.length
          + eventBytes
          + event.length
          + events.size();
    }

    private void add(final byte[] event) {
      events.add(event);
      eventBytes += event.length;
    }

    private EncodedPayload toPayload() {
      final int size =
          prefix.length + PAYLOAD_SUFFIX.length + eventBytes + Math.max(0, events.size() - 1);
      final byte[] body = new byte[size];
      int offset = 0;
      System.arraycopy(prefix, 0, body, offset, prefix.length);
      offset += prefix.length;
      for (int index = 0; index < events.size(); index++) {
        if (index > 0) {
          System.arraycopy(JSON_COMMA, 0, body, offset, JSON_COMMA.length);
          offset += JSON_COMMA.length;
        }
        final byte[] event = events.get(index);
        System.arraycopy(event, 0, body, offset, event.length);
        offset += event.length;
      }
      System.arraycopy(PAYLOAD_SUFFIX, 0, body, offset, PAYLOAD_SUFFIX.length);
      return new EncodedPayload(body, events.size());
    }
  }
}
