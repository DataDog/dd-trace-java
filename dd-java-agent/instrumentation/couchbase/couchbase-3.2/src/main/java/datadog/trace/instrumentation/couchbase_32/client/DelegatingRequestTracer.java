package datadog.trace.instrumentation.couchbase_32.client;

import com.couchbase.client.core.cnc.RequestSpan;
import com.couchbase.client.core.cnc.RequestTracer;
import com.couchbase.client.core.cnc.tracing.NoopRequestSpan;
import java.time.Duration;
import reactor.core.publisher.Mono;

public class DelegatingRequestTracer implements RequestTracer {

  private final DatadogRequestTracer ddTracer;
  private final RequestTracer cncTracer;

  public DelegatingRequestTracer(DatadogRequestTracer ddTracer, RequestTracer cncTracer) {
    this.ddTracer = ddTracer;
    this.cncTracer = cncTracer;
  }

  @Override
  public RequestSpan requestSpan(String name, RequestSpan parent) {
    RequestSpan ddParentSpan = unwrapDatadogParentSpan(parent);
    RequestSpan cncParentSpan = unwrapCncParentSpan(parent);

    RequestSpan ddSpan = ddTracer != null ? ddTracer.requestSpan(name, ddParentSpan) : null;
    RequestSpan cncSpan = cncTracer != null ? cncTracer.requestSpan(name, cncParentSpan) : null;

    // no tracers are present - return noop span
    if (ddSpan == null && cncSpan == null) {
      return NoopRequestSpan.INSTANCE;
    }

    // only one tracer is present - no need to delegate
    if (ddSpan == null) {
      return cncSpan;
    }
    if (cncSpan == null) {
      return ddSpan;
    }

    return new DelegatingRequestSpan(ddSpan, cncSpan);
  }

  @Override
  public Mono<Void> start() {
    Mono<Void> primary = ddTracer != null ? ddTracer.start() : Mono.empty();
    Mono<Void> secondary = cncTracer != null ? cncTracer.start() : Mono.empty();
    return Mono.when(primary, secondary);
  }

  @Override
  public Mono<Void> stop(Duration timeout) {
    Mono<Void> primary = ddTracer != null ? ddTracer.stop(timeout) : Mono.empty();
    Mono<Void> secondary = cncTracer != null ? cncTracer.stop(timeout) : Mono.empty();
    return Mono.when(primary, secondary);
  }

  private static RequestSpan unwrapDatadogParentSpan(RequestSpan parent) {
    if (parent instanceof DelegatingRequestSpan) {
      return ((DelegatingRequestSpan) parent).getDatadogSpan();
    }
    return parent;
  }

  private static RequestSpan unwrapCncParentSpan(RequestSpan parent) {
    if (parent instanceof DelegatingRequestSpan) {
      return ((DelegatingRequestSpan) parent).getCncSpan();
    }
    return parent;
  }
}
