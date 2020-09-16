package datadog.trace.instrumentation.servlet.dispatcher;

import datadog.trace.api.Function;
import datadog.trace.api.Functions;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class RequestDispatcherDecorator extends BaseDecorator {
  public static final DDCache<CharSequence, CharSequence> SPAN_NAME_CACHE =
      DDCaches.newUnboundedCache(16);
  public static final Function<CharSequence, CharSequence> SERVLET_PREFIX =
      Functions.Prefix.ZERO.curry("servlet.");
  public static final RequestDispatcherDecorator DECORATE = new RequestDispatcherDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"servlet", "servlet-dispatcher"};
  }

  @Override
  protected String spanType() {
    return null;
  }

  @Override
  protected String component() {
    return "java-web-servlet-dispatcher";
  }
}
