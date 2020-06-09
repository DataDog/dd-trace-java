package datadog.trace.core.taginterceptor;

import datadog.trace.core.DDSpanContext;

/**
 * Span decorators are called when new tags are written and proceed to various remappings and
 * enrichments
 */
public abstract class AbstractTagInterceptor {

  private String matchingTag;

  private Object matchingValue;

  private String replacementTag;

  private String replacementValue;

  public boolean shouldSetTag(final DDSpanContext context, final String tag, final Object value) {
    if (getMatchingValue() == null || getMatchingValue().equals(value)) {
      final String targetTag = getReplacementTag() == null ? tag : getReplacementTag();
      final String targetValue =
          getReplacementValue() == null ? String.valueOf(value) : getReplacementValue();

      context.setTag(targetTag, targetValue);
      return false;
    } else {
      return true;
    }
  }

  public String getMatchingTag() {
    return matchingTag;
  }

  public void setMatchingTag(final String tag) {
    matchingTag = tag;
  }

  public Object getMatchingValue() {
    return matchingValue;
  }

  public void setMatchingValue(final Object value) {
    matchingValue = value;
  }

  public String getReplacementTag() {
    return replacementTag;
  }

  public void setReplacementTag(final String targetTag) {
    replacementTag = targetTag;
  }

  public String getReplacementValue() {
    return replacementValue;
  }

  public void setReplacementValue(final String targetValue) {
    replacementValue = targetValue;
  }
}
