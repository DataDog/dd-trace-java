package datadog.trace.core.taginterceptor;

import datadog.trace.core.DDSpanContext;

/**
 * Span decorators are called when new tags are written and proceed to various remappings and
 * enrichments
 */
public abstract class AbstractTagInterceptor {

  private final String matchingTag;

  protected AbstractTagInterceptor(final String matchingTag) {
    this.matchingTag = matchingTag;
  }

  public abstract boolean shouldSetTag(
      final DDSpanContext context, final String tag, final Object value);

  public String getMatchingTag() {
    return matchingTag;
  }
}
