package stackstate.opentracing.decorators;

import stackstate.opentracing.STSSpanContext;
import stackstate.trace.api.STSTags;
import io.opentracing.tag.Tags;

/**
 * This span decorator leverages HTTP tags. It allows the dev to define a custom service name and
 * retrieves some HTTP meta such as the request path
 */
public class HTTPComponent extends AbstractDecorator {

  public HTTPComponent() {
    super();
    this.setMatchingTag(Tags.COMPONENT.getKey());
    this.setReplacementTag(STSTags.SERVICE_NAME);
  }

  @Override
  public boolean shouldSetTag(final STSSpanContext context, final String tag, final Object value) {
    if (getMatchingValue().equals(value)) {
      // Assign service name
      super.shouldSetTag(context, tag, value);
      // Assign span type to WEB
      context.setSpanType("web");
    }
    return true;
  }
}
