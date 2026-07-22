package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.api.DDTags.ANALYTICS_SAMPLE_RATE;
import static datadog.trace.api.DDTags.LANGUAGE_TAG_KEY;
import static datadog.trace.api.DDTags.LANGUAGE_TAG_VALUE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.COMPONENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The span state a decorator's {@code afterStart} is expected to apply, built up level by level to
 * mirror the {@code buildSpanPrototype()} extension chain (base identity, then server/client kind,
 * then any specialization). {@link #assertAppliedTo(RecordingSpan)} verifies the whole accumulated
 * state at once instead of asserting individual mock interactions.
 */
final class ExpectedSpanState {
  private CharSequence spanType;
  private final Map<String, String> tags = new LinkedHashMap<>();
  private CharSequence integrationName;

  private boolean expectService;
  private String serviceName;
  private CharSequence serviceNameSource;

  private boolean measured;

  // null => expect setMetric(null); non-null => expect the analytics-rate metric entry.
  private Double analyticsSampleRate;

  static ExpectedSpanState expected() {
    return new ExpectedSpanState();
  }

  ExpectedSpanState spanType(CharSequence type) {
    this.spanType = type;
    return this;
  }

  ExpectedSpanState tag(String key, CharSequence value) {
    tags.put(key, String.valueOf(value));
    return this;
  }

  /**
   * Baked component tag plus the integration name derived from it, as {@code BaseDecorator} does.
   */
  ExpectedSpanState component(CharSequence component) {
    tags.put(COMPONENT, String.valueOf(component));
    this.integrationName = component;
    return this;
  }

  ExpectedSpanState spanKind(CharSequence kind) {
    tags.put(SPAN_KIND, String.valueOf(kind));
    return this;
  }

  ExpectedSpanState language() {
    tags.put(LANGUAGE_TAG_KEY, LANGUAGE_TAG_VALUE);
    return this;
  }

  ExpectedSpanState serviceName(String serviceName, CharSequence source) {
    this.expectService = true;
    this.serviceName = serviceName;
    this.serviceNameSource = source;
    return this;
  }

  ExpectedSpanState measured(boolean measured) {
    this.measured = measured;
    return this;
  }

  ExpectedSpanState analyticsSampleRate(Double rate) {
    this.analyticsSampleRate = rate;
    return this;
  }

  /**
   * Lenient baseline check for the polymorphic base {@code afterStart} spec, which runs against
   * every subclass decorator: asserts the identity a decorator must apply (span type, the declared
   * tags as a subset, integration name) while tolerating the extra tags/state a subclass layers on.
   */
  void assertIdentityAppliedTo(RecordingSpan span) {
    assertEquals(str(spanType), str(span.recordedSpanType()), "span type");
    assertEquals(str(integrationName), str(span.recordedIntegrationName()), "integration name");
    for (Map.Entry<String, String> expectedTag : tags.entrySet()) {
      assertEquals(
          expectedTag.getValue(),
          span.recordedTags().get(expectedTag.getKey()),
          "tag " + expectedTag.getKey());
    }
  }

  /** Exact check: the recorded state must match exactly, with no additional tags. */
  void assertAppliedTo(RecordingSpan span) {
    assertAppliedTo(span, false);
  }

  /**
   * Scalar-exact check that tolerates additional tags, for a polymorphic parent spec (e.g. {@code
   * ClientDecoratorTest}) whose subclass decorators layer on extra tags in {@code afterStart}. Span
   * type, integration name, service, measured flag and metric are still asserted exactly.
   */
  void assertAppliedAllowingExtraTags(RecordingSpan span) {
    assertAppliedTo(span, true);
  }

  private void assertAppliedTo(RecordingSpan span, boolean allowExtraTags) {
    assertEquals(str(spanType), str(span.recordedSpanType()), "span type");
    if (allowExtraTags) {
      for (Map.Entry<String, String> expectedTag : tags.entrySet()) {
        assertEquals(
            expectedTag.getValue(),
            span.recordedTags().get(expectedTag.getKey()),
            "tag " + expectedTag.getKey());
      }
    } else {
      assertEquals(tags, span.recordedTags(), "applied tags");
    }
    assertEquals(str(integrationName), str(span.recordedIntegrationName()), "integration name");

    if (expectService) {
      assertTrue(span.serviceNameSet(), "expected setServiceName to be called");
      assertEquals(serviceName, span.recordedServiceName(), "service name");
      assertEquals(str(serviceNameSource), str(span.recordedServiceNameSource()), "service source");
    } else {
      assertFalse(span.serviceNameSet(), "did not expect setServiceName to be called");
    }

    assertEquals(measured, span.recordedMeasured(), "measured");

    if (analyticsSampleRate == null) {
      assertNull(span.recordedMetric(), "expected no analytics metric");
    } else {
      assertTrue(span.metricSet(), "expected setMetric to be called");
      assertEquals(ANALYTICS_SAMPLE_RATE, span.recordedMetric().tag(), "analytics metric key");
      assertEquals(
          analyticsSampleRate, span.recordedMetric().doubleValue(), "analytics metric value");
    }
  }

  private static String str(CharSequence value) {
    return value == null ? null : value.toString();
  }
}
