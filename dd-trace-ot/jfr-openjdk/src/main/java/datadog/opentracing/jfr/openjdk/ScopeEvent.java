package datadog.opentracing.jfr.openjdk;

import datadog.opentracing.DDSpanContext;
import datadog.opentracing.jfr.DDScopeEvent;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("datadog.Scope")
@Label("Scope")
@Description("Datadog event corresponding to a scope.")
@Category("Datadog")
@StackTrace(false)
public final class ScopeEvent extends Event implements DDScopeEvent {

  private final transient DDSpanContext spanContext;

  @Label("Trace Id")
  private String traceId;

  @Label("Span Id")
  private String spanId;

  @Label("Parent Id")
  private String parentId;

  @Label("Service Name")
  private String serviceName;

  @Label("Resource Name")
  private String resourceName;

  @Label("Operation Name")
  private String operationName;

  ScopeEvent(final DDSpanContext spanContext) {
    this.spanContext = spanContext;
  }

  @Override
  public void start() {
    if (isEnabled()) {
      begin();
    }
  }

  @Override
  public void finish() {
    if (shouldCommit()) {
      traceId = spanContext.getTraceId();
      spanId = spanContext.getSpanId();
      parentId = spanContext.getParentId();
      serviceName = spanContext.getServiceName();
      resourceName = spanContext.getResourceName();
      operationName = spanContext.getOperationName();

      end();
      commit();
    }
  }
}
