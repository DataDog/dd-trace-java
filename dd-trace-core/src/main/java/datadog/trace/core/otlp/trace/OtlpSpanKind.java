package datadog.trace.core.otlp.trace;

import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CONSUMER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_PRODUCER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_SERVER;

/** Maps Datadog span-kind tags to OTLP's {@code Span.SpanKind} enum values. */
final class OtlpSpanKind {
  private OtlpSpanKind() {}

  static int spanKind(CharSequence spanKind) {
    if (spanKind == null) {
      return 0; // UNSPECIFIED
    } else if (SPAN_KIND_SERVER.contentEquals(spanKind)) {
      return 2; // SERVER
    } else if (SPAN_KIND_CLIENT.contentEquals(spanKind)) {
      return 3; // CLIENT
    } else if (SPAN_KIND_PRODUCER.contentEquals(spanKind)) {
      return 4; // PRODUCER
    } else if (SPAN_KIND_CONSUMER.contentEquals(spanKind)) {
      return 5; // CONSUMER
    } else {
      return 1; // INTERNAL
    }
  }
}
