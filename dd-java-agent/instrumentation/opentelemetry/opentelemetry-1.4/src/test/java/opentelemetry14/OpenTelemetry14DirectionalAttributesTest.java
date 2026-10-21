package opentelemetry14;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.test.junit.utils.assertions.Matchers.is;

import datadog.opentelemetry.shim.trace.OtelConventions;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import org.tabletest.junit.TableTest;

/**
 * A few OpenTelemetry attribute names denote a different Datadog tag depending on the span's kind:
 * {@code server.address} is {@code http.hostname} on a server span and {@code peer.hostname} on a
 * client span. The shim resolves them by the kind, so the tracer core only sees Datadog names.
 */
public class OpenTelemetry14DirectionalAttributesTest extends AbstractOpenTelemetry14Test {

  @TableTest({
    "scenario                        | kind     | when        | attribute      | tag           ",
    "client address, kind first      | CLIENT   | after kind  | server.address | peer.hostname ",
    "client address, attribute first | CLIENT   | before kind | server.address | peer.hostname ",
    "client address, after start     | CLIENT   | after start | server.address | peer.hostname ",
    "server address, kind first      | SERVER   | after kind  | server.address | http.hostname ",
    "server address, attribute first | SERVER   | before kind | server.address | http.hostname ",
    "server address, after start     | SERVER   | after start | server.address | http.hostname ",
    "producer address                | PRODUCER | after kind  | server.address | peer.hostname ",
    "internal address stays as is    | INTERNAL | after kind  | server.address | server.address"
  })
  void aDirectionScopedAttributeIsStoredAsTheTagItDenotes(
      String kind, String when, String attribute, String tag) {
    SpanBuilder builder = this.otelTracer.spanBuilder("some-name");
    if ("before kind".equals(when)) {
      builder.setAttribute(attribute, "example.com");
    }
    builder.setSpanKind(SpanKind.valueOf(kind));
    if ("after kind".equals(when)) {
      builder.setAttribute(attribute, "example.com");
    }
    Span span = builder.startSpan();
    if ("after start".equals(when)) {
      span.setAttribute(attribute, "example.com");
    }
    span.end();

    assertTraces(
        trace(
            span()
                .root()
                .tags(
                    defaultTags(),
                    tag(SPAN_KIND, is(OtelConventions.toSpanKindTagValue(SpanKind.valueOf(kind)))),
                    tag(tag, is("example.com")))));
  }

  @TableTest({
    "scenario | kind   | tag          ",
    "client   | CLIENT | peer.hostname",
    "server   | SERVER | http.hostname"
  })
  void aWriteAfterTheKindWinsOverOneHeldBeforeIt(String kind, String tag) {
    Span span =
        this.otelTracer
            .spanBuilder("some-name")
            .setAttribute("server.address", "old.example.com")
            .setSpanKind(SpanKind.valueOf(kind))
            .setAttribute("server.address", "new.example.com")
            .startSpan();
    span.end();

    assertTraces(
        trace(
            span()
                .root()
                .tags(
                    defaultTags(),
                    tag(SPAN_KIND, is(OtelConventions.toSpanKindTagValue(SpanKind.valueOf(kind)))),
                    tag(tag, is("new.example.com")))));
  }

  @TableTest({
    "scenario    | kind   | when       ",
    "before kind | CLIENT | before kind",
    "after kind  | CLIENT | after kind ",
    "no kind     |        | before kind"
  })
  void aNullAttributeKeyIsIgnored(String kind, String when) {
    SpanBuilder builder = this.otelTracer.spanBuilder("some-name");
    if ("before kind".equals(when)) {
      setNullKeyAttributes(builder);
    }
    if (kind != null) {
      builder.setSpanKind(SpanKind.valueOf(kind));
    }
    if ("after kind".equals(when)) {
      setNullKeyAttributes(builder);
    }
    builder.startSpan().end();

    SpanKind spanKind = kind == null ? SpanKind.INTERNAL : SpanKind.valueOf(kind);
    assertTraces(
        trace(
            span()
                .root()
                .tags(
                    defaultTags(),
                    tag(SPAN_KIND, is(OtelConventions.toSpanKindTagValue(spanKind))))));
  }

  private static void setNullKeyAttributes(SpanBuilder builder) {
    builder
        .setAttribute((String) null, "value")
        .setAttribute((String) null, 1L)
        .setAttribute((String) null, 1.0)
        .setAttribute((String) null, true);
  }

  @TableTest({
    "scenario           | kind   | tag        ",
    "client server.port | CLIENT | peer.port  ",
    "server server.port | SERVER | server.port"
  })
  void serverPortIsThePeerPortOnClientSpansOnly(String kind, String tag) {
    Span span =
        this.otelTracer
            .spanBuilder("some-name")
            .setAttribute("server.port", 8443L)
            .setSpanKind(SpanKind.valueOf(kind))
            .startSpan();
    span.end();

    assertTraces(
        trace(
            span()
                .root()
                .tags(
                    defaultTags(),
                    tag(SPAN_KIND, is(OtelConventions.toSpanKindTagValue(SpanKind.valueOf(kind)))),
                    tag(tag, is(8443L)))));
  }
}
