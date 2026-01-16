package datadog.trace.instrumentation.servlet.http;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class HttpServletDecorator extends BaseDecorator {

  public static final CharSequence JAVA_WEB_SERVLET_SERVICE =
      UTF8BytesString.create("java-web-servlet-service");
  public static final HttpServletDecorator DECORATE = new HttpServletDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"servlet-service"};
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return JAVA_WEB_SERVLET_SERVICE;
  }
}
