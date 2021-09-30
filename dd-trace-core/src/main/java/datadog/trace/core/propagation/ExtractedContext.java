package datadog.trace.core.propagation;

import datadog.trace.api.DDId;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Propagated data resulting from calling tracer.extract with header data from an incoming request.
 */
public class ExtractedContext extends TagContext {
  private final DDId traceId;
  private final DDId spanId;
  private final int samplingPriority;
  private final Map<String, String> baggage;
  private final AtomicBoolean samplingPriorityLocked = new AtomicBoolean(false);

  public ExtractedContext(
      final DDId traceId,
      final DDId spanId,
      final int samplingPriority,
      final String origin,
      final String forwarded,
      final String forwardedProto,
      final String forwardedHost,
      final String forwardedIp,
      final String forwardedPort,
      final Map<String, String> baggage,
      final Map<String, String> tags) {
    super(origin, forwarded, forwardedProto, forwardedHost, forwardedIp, forwardedPort, tags);
    this.traceId = traceId;
    this.spanId = spanId;
    this.samplingPriority = samplingPriority;
    this.baggage = baggage;
  }

  @Override
  public Iterable<Map.Entry<String, String>> baggageItems() {
    return baggage.entrySet();
  }

  public void lockSamplingPriority() {
    samplingPriorityLocked.set(true);
  }

  @Override
  public DDId getTraceId() {
    return traceId;
  }

  @Override
  public DDId getSpanId() {
    return spanId;
  }

  public int getSamplingPriority() {
    return samplingPriority;
  }

  public Map<String, String> getBaggage() {
    return baggage;
  }

  public boolean getSamplingPriorityLocked() {
    return samplingPriorityLocked.get();
  }
}
