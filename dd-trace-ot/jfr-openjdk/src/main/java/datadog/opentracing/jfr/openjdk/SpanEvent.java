package datadog.opentracing.jfr.openjdk;

import datadog.opentracing.DDSpanContext;
import datadog.opentracing.jfr.DDSpanEvent;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("datadog.Span")
@Label("Span")
@Description(
    "Datadog event corresponding to a span. Note: this event may be started on a different thread.")
@Category("Datadog")
@StackTrace(false)
public class SpanEvent extends Event implements DDSpanEvent {

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

  SpanEvent(final DDSpanContext spanContext) {
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

      // OpenJDK records end thread for us so we do not need to keep a field for it.
      // Note: this implementation will not work on OracleJDK below 9 since it doesn't support
      // starting and ending events on different threads
      end();
      commit();
    }
  }
}
