package datadog.trace.core.otlp.trace;

import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CONSUMER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_PRODUCER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_SERVER;

/**
 * Maps Datadog span-kind tags to OTLP's {@code Span.SpanKind} enum values.
 */
final class OtlpSpanKind {
  private OtlpSpanKind() {
  }

  static int spanKind(CharSequence spanKind) {
    if (spanKind == null) {
      // UNSPECIFIED
      return 0;
    } else if (SPAN_KIND_SERVER.contentEquals(spanKind)) {
      // SERVER
      return 2;
    } else if (SPAN_KIND_CLIENT.contentEquals(spanKind)) {
      // CLIENT
      return 3;
    } else if (SPAN_KIND_PRODUCER.contentEquals(spanKind)) {
      // PRODUCER
      return 4;
    } else if (SPAN_KIND_CONSUMER.contentEquals(spanKind)) {
      // CONSUMER
      return 5;
    } else {
      // INTERNAL
      return 1;
    }
  }
}
