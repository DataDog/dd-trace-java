package datadog.trace.instrumentation.couchbase_31.client;

import com.couchbase.client.core.cnc.RequestSpan;
import com.couchbase.client.core.cnc.RequestTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.time.Duration;
import reactor.core.publisher.Mono;

public class DatadogRequestTracer implements RequestTracer {
  private static final CharSequence COUCHBASE_CALL = UTF8BytesString.create("couchbase.call");

  private final AgentTracer.TracerAPI tracer;

  public DatadogRequestTracer(AgentTracer.TracerAPI tracer) {
    this.tracer = tracer;
  }

  @Override
  public RequestSpan requestSpan(String requestName, RequestSpan requestParent) {
    AgentTracer.SpanBuilder builder = tracer.buildSpan(COUCHBASE_CALL);
    AgentSpan parent = DatadogRequestSpan.unwrap(requestParent);
    if (parent == null) {
      parent = tracer.activeSpan();
    }
    if (parent != null) {
      builder.asChildOf(parent.context());
    }
    AgentSpan span = builder.start();
    CouchbaseClientDecorator.DECORATE.afterStart(span);
    span.setResourceName(requestName);
    return DatadogRequestSpan.wrap(span);
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
