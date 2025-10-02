package datadog.trace.instrumentation.servlet.http;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class HttpServletResponseDecorator extends BaseDecorator {
  public static final CharSequence SERVLET_RESPONSE = UTF8BytesString.create("servlet.response");
  public static final CharSequence JAVA_WEB_SERVLET_RESPONSE =
      UTF8BytesString.create("java-web-servlet-response");
  public static final HttpServletResponseDecorator DECORATE = new HttpServletResponseDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"servlet", "servlet-response"};
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return JAVA_WEB_SERVLET_RESPONSE;
  }
}
