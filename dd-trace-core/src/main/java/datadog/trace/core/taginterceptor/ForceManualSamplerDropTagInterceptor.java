package datadog.trace.core.taginterceptor;

import datadog.trace.api.DDTags;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.core.ExclusiveSpan;

/**
 * Tag decorator to replace tag 'manual.sampler_drop: true' with the appropriate priority sampling
 * value.
 */
class ForceManualSamplerDropTagInterceptor extends AbstractTagInterceptor {

  public ForceManualSamplerDropTagInterceptor() {
    super(DDTags.MANUAL_SAMPLER_DROP);
  }

  @Override
  public boolean shouldSetTag(final ExclusiveSpan span, final String tag, final Object value) {
    if (value instanceof Boolean && (boolean) value) {
      span.setSamplingPriority(PrioritySampling.SAMPLER_DROP);
    } else if (value instanceof String && Boolean.parseBoolean((String) value)) {
      span.setSamplingPriority(PrioritySampling.SAMPLER_DROP);
    }
    return false;
  }
}
