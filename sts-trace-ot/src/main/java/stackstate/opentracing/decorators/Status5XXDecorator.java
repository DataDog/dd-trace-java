package stackstate.opentracing.decorators;

import io.opentracing.tag.Tags;
import stackstate.opentracing.STSSpanContext;

/** Mark all 5xx status codes as an error */
public class Status5XXDecorator extends AbstractDecorator {
  public Status5XXDecorator() {
    super();
    this.setMatchingTag(Tags.HTTP_STATUS.getKey());
  }

  @Override
  public boolean afterSetTag(final STSSpanContext context, final String tag, final Object value) {
    if (Tags.HTTP_STATUS.getKey().equals(tag)) {
      final int responseCode = Integer.parseInt(value.toString());
      if (500 <= responseCode && responseCode < 600) {
        context.setTag(Tags.ERROR.getKey(), true);
        return true;
      }
    }
    return false;
  }
}
