package datadog.trace.instrumentation.mule4;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.MULE_CORRELATION_ID;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.MULE_LOCATION;

import datadog.trace.api.Functions;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import java.util.function.Function;
import org.mule.runtime.api.component.Component;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.tracer.api.span.info.InitialSpanInfo;

public class MuleDecorator extends BaseDecorator {
  private static final CharSequence MULE = UTF8BytesString.create("mule");
  private static final CharSequence OPERATION_NAME = UTF8BytesString.create("mule.action");
  public static final MuleDecorator DECORATE = new MuleDecorator();
  private static final DDCache<CharSequence, String> TAG_CACHE = DDCaches.newFixedSizeCache(128);
  private static final Function<CharSequence, String> TAG_ADDER =
      new Functions.Prefix("mule.").andThen(new Functions.ToString<>());
  private static final DDCache<Component, String> COMPONENT_DOC_CACHE =
      DDCaches.newFixedSizeCache(1014);
  private static final Function<Component, String> COMPONENT_DOC_ADDER =
      component -> {
        final Object ret = component.getAnnotation(Component.Annotations.NAME_ANNOTATION_KEY);
        if (ret != null) {
          return ret.toString();
        }
        return null;
      };

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

  public AgentSpan onMuleSpan(
      AgentSpan parentSpan, InitialSpanInfo spanInfo, CoreEvent event, Component component) {
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
    // here we have to use the forEachAttribute since each specialized InitialSpanInfo class can add
    // different things through this method. Using the map version is not the same.
    spanInfo.forEachAttribute((s, s2) -> span.setTag(TAG_CACHE.computeIfAbsent(s, TAG_ADDER), s2));
    span.setTag(MULE_CORRELATION_ID, event.getCorrelationId());
    // cache the resource name might be complex since it depends on a couple of keys
    String extraDetail = null;
    if (component != null) {
      extraDetail = COMPONENT_DOC_CACHE.computeIfAbsent(component, COMPONENT_DOC_ADDER);
    }
    if (extraDetail == null) {
      extraDetail = (String) span.getTag(MULE_LOCATION);
    }
    if (extraDetail != null) {
      span.setResourceName(spanInfo.getName() + " " + extraDetail);
    } else {
      span.setResourceName(spanInfo.getName());
    }
    return afterStart(span);
  }
}
