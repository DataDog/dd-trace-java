package datadog.trace.core.taginterceptor;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpanContext;

public class PeerServiceTagInterceptor extends AbstractTagInterceptor {
  public PeerServiceTagInterceptor() {
    super();
    setMatchingTag(Tags.PEER_SERVICE);
  }

  @Override
  public boolean shouldSetTag(final DDSpanContext context, final String tag, final Object value) {
    context.setServiceName(String.valueOf(value));
    return false;
  }
}
