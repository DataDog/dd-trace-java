package stackstate.opentracing.decorators;

import stackstate.opentracing.STSSpanContext;
import stackstate.trace.api.STSTags;

/**
 * Span decorators are called when new tags are written and proceed to various remappings and
 * enrichments
 */
public abstract class AbstractDecorator {

  private String matchingTag;

  private Object matchingValue;

  private String setTag;

  private String setValue;

  public boolean afterSetTag(final STSSpanContext context, final String tag, final Object value) {
    if (this.getMatchingValue() == null || this.getMatchingValue().equals(value)) {
      final String targetTag = getSetTag() == null ? tag : getSetTag();
      final String targetValue = getSetValue() == null ? String.valueOf(value) : getSetValue();

      if (targetTag.equals(STSTags.SERVICE_NAME)) {
        context.setServiceName(targetValue);
      } else if (targetTag.equals(STSTags.RESOURCE_NAME)) {
        context.setResourceName(targetValue);
      } else if (targetTag.equals(STSTags.SPAN_TYPE)) {
        context.setSpanType(targetValue);
      } else {
        context.setTag(targetTag, targetValue);
      }
      return true;
    } else {
      return false;
    }
  }

  public String getMatchingTag() {
    return matchingTag;
  }

  public void setMatchingTag(final String tag) {
    this.matchingTag = tag;
  }

  public Object getMatchingValue() {
    return matchingValue;
  }

  public void setMatchingValue(final Object value) {
    this.matchingValue = value;
  }

  public String getSetTag() {
    return setTag;
  }

  public void setSetTag(final String targetTag) {
    this.setTag = targetTag;
  }

  public String getSetValue() {
    return setValue;
  }

  public void setSetValue(final String targetValue) {
    this.setValue = targetValue;
  }
}
