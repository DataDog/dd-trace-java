package datadog.trace.core.propagation;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Propagated data resulting from calling tracer.extract with header data from an incoming request.
 */
public class ExtractedContext extends TagContext {
  private final long traceId;
  private final long spanId;
  private final int samplingPriority;
  private final Map<String, String> baggage;
  private final AtomicBoolean samplingPriorityLocked = new AtomicBoolean(false);

  public ExtractedContext(
      final long traceId,
      final long spanId,
      final int samplingPriority,
      final String origin,
      final Map<String, String> baggage,
      final Map<String, String> tags) {
    super(origin, tags);
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

  public long getTraceId() {
    return traceId;
  }

  public long getSpanId() {
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
