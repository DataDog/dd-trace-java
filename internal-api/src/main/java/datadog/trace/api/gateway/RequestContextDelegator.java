package datadog.trace.api.gateway;

import java.util.LinkedList;
import java.util.List;

/**
 * This class retain and call all delegated implementations
 */
@SuppressWarnings("rawtypes")
public class RequestContextDelegator implements RequestContext {

  private final List<RequestContext> ctxDelegates = new LinkedList<>();

  public void addDelegate(RequestContext ctx) {
    ctxDelegates.add(ctx);
  }

  @Override
  public void addHeader(String key, String value) {
    for (RequestContext ctxDelegate : ctxDelegates) {
      ctxDelegate.addHeader(key, value);
    }
  }

  @Override
  public void addCookie(String key, String value) {
    for (RequestContext ctxDelegate : ctxDelegates) {
      ctxDelegate.addCookie(key, value);
    }
  }

  @Override
  public Flow finishHeaders() {
    Flow flow = null;
    for (RequestContext ctxDelegate : ctxDelegates) {
      flow = ctxDelegate.finishHeaders();
    }
    return flow;
  }

  @Override
  public Flow setRawURI(String uri) {
    Flow flow = null;
    for (RequestContext ctxDelegate : ctxDelegates) {
      flow = ctxDelegate.setRawURI(uri);
    }
    return flow;
  }
}
