package datadog.trace.core.decorators;

import datadog.trace.core.DDSpanContext;
import datadog.trace.bootstrap.instrumentation.api.Tags;

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
