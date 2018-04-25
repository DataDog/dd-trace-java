package stackstate.opentracing.decorators;

import io.opentracing.tag.Tags;
import stackstate.trace.api.STSTags;

/** This span decorator protect against spam on the resource name */
public class Status404Decorator extends AbstractDecorator {

  public Status404Decorator() {
    super();
    this.setMatchingTag(Tags.HTTP_STATUS.getKey());
    this.setMatchingValue(404);
    this.setSetTag(STSTags.RESOURCE_NAME);
    this.setSetValue("404");
  }
}
