package datadog.opentelemetry.shim.trace;

import datadog.opentelemetry.shim.context.propagation.TraceStateHelper;
import datadog.opentelemetry.shim.trace.utils.AttributesUtils;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.SpanLink;
import io.opentelemetry.api.trace.SpanContext;

public class OtelSpanLink extends SpanLink {
  public OtelSpanLink(SpanContext spanContext) {
    this(spanContext, io.opentelemetry.api.common.Attributes.empty());
  }

  public OtelSpanLink(SpanContext spanContext, io.opentelemetry.api.common.Attributes attributes) {
    super(
        DDTraceId.fromHex(spanContext.getTraceId()),
        DDSpanId.fromHex(spanContext.getSpanId()),
        spanContext.isSampled() ? SAMPLED_FLAG : DEFAULT_FLAGS,
        TraceStateHelper.encodeHeader(spanContext.getTraceState()),
        AttributesUtils.convertAttributes(attributes));
  }
}
