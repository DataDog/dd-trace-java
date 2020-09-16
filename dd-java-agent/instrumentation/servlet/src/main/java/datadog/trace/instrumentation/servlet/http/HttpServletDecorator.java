package datadog.trace.instrumentation.servlet.http;

import datadog.trace.api.Function;
import datadog.trace.api.Functions;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class HttpServletDecorator extends BaseDecorator {
  public static final DDCache<CharSequence, CharSequence> SPAN_NAME_CACHE =
      DDCaches.newUnboundedCache(16);
  public static final Function<CharSequence, CharSequence> SERVLET_PREFIX =
      Functions.Prefix.ZERO.curry("servlet.");
  public static final HttpServletDecorator DECORATE = new HttpServletDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"servlet-service"};
  }

  @Override
  protected String spanType() {
    return null;
  }

  @Override
  protected String component() {
    return "java-web-servlet-service";
  }
}
