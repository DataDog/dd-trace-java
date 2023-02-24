package datadog.trace.instrumentation.couchbase_31.client;

import static datadog.trace.instrumentation.couchbase_31.client.CouchbaseClientDecorator.COUCHBASE_CLIENT;

import com.couchbase.client.core.cnc.RequestSpan;
import com.couchbase.client.core.cnc.RequestTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.time.Duration;
import reactor.core.publisher.Mono;

public class DatadogRequestTracer implements RequestTracer {
  private static final CharSequence COUCHBASE_INTERNAL =
      UTF8BytesString.create("couchbase.internal");

  private final AgentTracer.TracerAPI tracer;

  public DatadogRequestTracer(AgentTracer.TracerAPI tracer) {
    this.tracer = tracer;
  }

  @Override
  public RequestSpan requestSpan(String requestName, RequestSpan requestParent) {
    CharSequence spanName = CouchbaseClientDecorator.OPERATION_NAME;
    boolean measured = true;

    AgentSpan parent = DatadogRequestSpan.unwrap(requestParent);
    if (null == parent) {
      parent = tracer.activeSpan();
    }
    if (null != parent && COUCHBASE_CLIENT.equals(parent.getTag(Tags.COMPONENT))) {
      spanName = COUCHBASE_INTERNAL;
      measured = false;
    }

    AgentTracer.SpanBuilder builder = tracer.buildSpan(spanName);
    if (null != parent) {
      builder.asChildOf(parent.context());
    }
    AgentSpan span = builder.start();
    CouchbaseClientDecorator.DECORATE.afterStart(span);
    span.setResourceName(requestName);
    span.setMeasured(measured);
    DatadogRequestSpan requestSpan = DatadogRequestSpan.wrap(span);
    requestSpan.setParent(requestParent);
    return requestSpan;
  }

  @Override
  public Mono<Void> start() {
    return Mono.empty(); // Tracer already exists
  }

  @Override
  public Mono<Void> stop(Duration timeout) {
    return Mono.empty(); // Tracer should continue to exist
  }
}
