package datadog.trace.core.taginterceptor;

import datadog.trace.api.DDTags;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.core.DDSpanContext;

/**
 * Tag decorator to replace tag 'manual.keep: true' with the appropriate priority sampling value.
 */
class ForceManualKeepTagInterceptor extends AbstractTagInterceptor {

  public ForceManualKeepTagInterceptor() {
    super(DDTags.MANUAL_KEEP);
  }

  @Override
  public boolean shouldSetTag(final DDSpanContext context, final String tag, final Object value) {
    if (value instanceof Boolean && (boolean) value) {
      context.setSamplingPriority(PrioritySampling.USER_KEEP);
    } else if (value instanceof String && Boolean.parseBoolean((String) value)) {
      context.setSamplingPriority(PrioritySampling.USER_KEEP);
    }
    return false;
  }
}
