package datadog.trace.core.taginterceptor;

import datadog.trace.api.DDTags;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.core.ExclusiveSpan;

/**
 * Tag decorator to replace tag 'manual.drop: true' with the appropriate priority sampling value.
 */
class ForceManualDropTagInterceptor extends AbstractTagInterceptor {

  public ForceManualDropTagInterceptor() {
    super(DDTags.MANUAL_DROP);
  }

  @Override
  public boolean shouldSetTag(final ExclusiveSpan span, final String tag, final Object value) {
    if (value instanceof Boolean && (boolean) value) {
      span.setSamplingPriority(PrioritySampling.USER_DROP);
    } else if (value instanceof String && Boolean.parseBoolean((String) value)) {
      span.setSamplingPriority(PrioritySampling.USER_DROP);
    }
    return false;
  }
}
