package datadog.trace.instrumentation.couchbase_32.client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.blackholeSpan;
import static datadog.trace.instrumentation.couchbase_32.client.CouchbaseClientDecorator.COUCHBASE_CLIENT;
import static datadog.trace.instrumentation.couchbase_32.client.CouchbaseClientDecorator.OPERATION_NAME;

import com.couchbase.client.core.Core;
import com.couchbase.client.core.cnc.RequestSpan;
import com.couchbase.client.core.cnc.RequestTracer;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.time.Duration;
import reactor.core.publisher.Mono;

public class DatadogRequestTracer implements RequestTracer {
  private static final CharSequence COUCHBASE_INTERNAL =
      UTF8BytesString.create("couchbase.internal");

  private final AgentTracer.TracerAPI tracer;

  private final ContextStore<Core, String> coreContext;

  public DatadogRequestTracer(
      AgentTracer.TracerAPI tracer, final ContextStore<Core, String> coreContext) {
    this.tracer = tracer;
    this.coreContext = coreContext;
  }

  @Override
  public RequestSpan requestSpan(String requestName, RequestSpan requestParent) {
    CharSequence spanName = OPERATION_NAME;
    boolean measured = true;
    Object seedNodes = null;

    AgentSpan parent = DatadogRequestSpan.unwrap(requestParent);
    if (null == parent) {
      parent = tracer.activeSpan();
    }
    DatadogRequestSpan requestSpan = null;

    if (null != parent && COUCHBASE_CLIENT.equals(parent.getTag(Tags.COMPONENT))) {
      if (!Config.get().isCouchbaseInternalSpansEnabled()) {
        // mute the tracing related to internal spans
        requestSpan = DatadogRequestSpan.wrap(blackholeSpan(), coreContext);
      } else {
        spanName = COUCHBASE_INTERNAL;
        measured = false;
        seedNodes = parent.getTag(InstrumentationTags.COUCHBASE_SEED_NODES);
      }
    }
    if (requestSpan == null) {
      AgentTracer.SpanBuilder builder = tracer.singleSpanBuilder(spanName);
      if (null != parent) {
        builder.asChildOf(parent.context());
      }
      AgentSpan span = builder.start();
      CouchbaseClientDecorator.DECORATE.afterStart(span);
      span.setResourceName(requestName);
      span.setMeasured(measured);
      if (seedNodes != null) {
        span.setTag(InstrumentationTags.COUCHBASE_SEED_NODES, seedNodes);
      }
      requestSpan = DatadogRequestSpan.wrap(span, coreContext);
    }
    // When Couchbase converts a query to a prepare statement or execute statement,
    // it will not finish the original span
    switch (requestName) {
      case "prepare":
      case "execute":
        requestSpan.setConvertedParent(requestParent);
        break;
      default:
        break;
    }

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
