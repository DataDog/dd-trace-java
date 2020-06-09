package datadog.trace.core.taginterceptor;

import datadog.trace.api.DDTags;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.core.DDSpanContext;

/**
 * Tag decorator to replace tag 'manual.drop: true' with the appropriate priority sampling value.
 */
public class ForceManualDropTagInterceptor extends AbstractTagInterceptor {

  public ForceManualDropTagInterceptor() {
    super();
    setMatchingTag(DDTags.MANUAL_DROP);
  }

  @Override
  public boolean shouldSetTag(final DDSpanContext context, final String tag, final Object value) {
    if (value instanceof Boolean && (boolean) value) {
      context.setSamplingPriority(PrioritySampling.USER_DROP);
    } else if (value instanceof String && Boolean.parseBoolean((String) value)) {
      context.setSamplingPriority(PrioritySampling.USER_DROP);
    }
    return false;
  }
}
