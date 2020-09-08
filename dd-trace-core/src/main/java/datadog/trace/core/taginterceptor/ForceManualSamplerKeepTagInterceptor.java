package datadog.trace.core.taginterceptor;

import datadog.trace.api.DDTags;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.core.ExclusiveSpan;

/**
 * Tag decorator to replace tag 'manual.sampler_keep: true' with the appropriate priority sampling
 * value.
 */
class ForceManualSamplerKeepTagInterceptor extends AbstractTagInterceptor {

  public ForceManualSamplerKeepTagInterceptor() {
    super(DDTags.MANUAL_SAMPLER_KEEP);
  }

  @Override
  public boolean shouldSetTag(final ExclusiveSpan span, final String tag, final Object value) {
    if (value instanceof Boolean && (boolean) value) {
      span.setSamplingPriority(PrioritySampling.SAMPLER_KEEP);
    } else if (value instanceof String && Boolean.parseBoolean((String) value)) {
      span.setSamplingPriority(PrioritySampling.SAMPLER_KEEP);
    }
    return false;
  }
}
