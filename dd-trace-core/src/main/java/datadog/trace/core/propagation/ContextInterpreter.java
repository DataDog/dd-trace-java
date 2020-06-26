package datadog.trace.core.propagation;

import datadog.trace.api.DDId;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public abstract class ContextInterpreter
    implements AgentPropagation.KeyValueConsumer, AgentPropagation.KeyClassifier {

  protected final Map<String, String> taggedHeaders;

  protected DDId traceId;
  protected DDId spanId;
  protected int samplingPriority;
  protected Map<String, String> tags;
  protected Map<String, String> baggage;
  protected String origin;
  protected boolean valid;

  protected ContextInterpreter(Map<String, String> taggedHeaders) {
    this.taggedHeaders = taggedHeaders;
    reset();
  }

  public abstract static class Factory {

    public ContextInterpreter create(Map<String, String> tagsMapping) {
      return construct(cleanMapping(tagsMapping));
    }

    protected abstract ContextInterpreter construct(Map<String, String> tagsMapping);

    protected Map<String, String> cleanMapping(Map<String, String> taggedHeaders) {
      final Map<String, String> cleanedMapping = new HashMap<>(taggedHeaders.size() * 4 / 3);
      for (Map.Entry<String, String> association : taggedHeaders.entrySet()) {
        cleanedMapping.put(
            association.getKey().trim().toLowerCase(), association.getValue().trim().toLowerCase());
      }
      return cleanedMapping;
    }
  }

  public ContextInterpreter reset() {
    traceId = DDId.ZERO;
    spanId = DDId.ZERO;
    samplingPriority = defaultSamplingPriority();
    origin = null;
    tags = Collections.emptyMap();
    baggage = Collections.emptyMap();
    valid = true;
    return this;
  }

  TagContext build() {
    if (valid) {
      if (!DDId.ZERO.equals(traceId)) {
        final ExtractedContext context =
            new ExtractedContext(traceId, spanId, samplingPriority, origin, baggage, tags);
        context.lockSamplingPriority();
        return context;
      } else if (origin != null || !tags.isEmpty()) {
        return new TagContext(origin, tags);
      }
    }
    return null;
  }

  protected void invalidateContext() {
    this.valid = false;
  }

  protected int defaultSamplingPriority() {
    return PrioritySampling.UNSET;
  }
}
