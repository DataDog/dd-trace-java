package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class SpanKindTest {

  @Test
  void ofRecognizesEveryStandardTagValue() {
    for (SpanKind kind : SpanKind.values()) {
      assertSame(kind, SpanKind.of(kind.tagValue()));
      assertSame(kind, SpanKind.of(UTF8BytesString.create(kind.tagValue())));
    }
  }

  @Test
  void ofReturnsNullForNonStandardKinds() {
    assertNull(SpanKind.of(null));
    assertNull(SpanKind.of(Tags.SPAN_KIND_TEST));
    assertNull(SpanKind.of("custom"));
  }
}
