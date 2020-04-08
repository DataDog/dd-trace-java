package datadog.trace.core.decorators;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpanContext;

public class PeerServiceDecorator extends AbstractDecorator {
  public PeerServiceDecorator() {
    super();
    this.setMatchingTag(Tags.PEER_SERVICE);
  }

  @Override
  public boolean shouldSetTag(final DDSpanContext context, final String tag, final Object value) {
    context.setServiceName(String.valueOf(value));
    return false;
  }
}
