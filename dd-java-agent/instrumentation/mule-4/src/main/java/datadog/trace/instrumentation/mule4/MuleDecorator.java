package datadog.trace.instrumentation.mule4;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import org.mule.runtime.tracer.api.span.info.InitialSpanInfo;

public class MuleDecorator extends BaseDecorator {
  private static final CharSequence MULE = UTF8BytesString.create("mule");
  private static final CharSequence OPERATION_NAME = UTF8BytesString.create("mule.action");
  public static final MuleDecorator DECORATE = new MuleDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {MULE.toString()};
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.MULE;
  }

  @Override
  protected CharSequence component() {
    return MULE;
  }

  @Override
  public AgentSpan afterStart(final AgentSpan span) {
    span.setMeasured(true);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_INTERNAL);
    return super.afterStart(span);
  }

  public AgentSpan onMuleSpan(AgentSpan parentSpan, InitialSpanInfo spanInfo) {
    // we stick with the same level of detail of OTEL exporter.
    // if not exportable we're not going to create a real span but we still need to track those
    // spans to keep a correct hierarchy.
    if (spanInfo.getInitialExportInfo() != null
        && !spanInfo.getInitialExportInfo().isExportable()) {
      return null;
    }
    final AgentSpan span;
    if (parentSpan == null) {
      span = startSpan(OPERATION_NAME);
    } else {
      span = startSpan(OPERATION_NAME, parentSpan.context());
    }
    span.setResourceName(spanInfo.getName());
    spanInfo.forEachAttribute(span::setTag);

    return afterStart(span);
  }
}
