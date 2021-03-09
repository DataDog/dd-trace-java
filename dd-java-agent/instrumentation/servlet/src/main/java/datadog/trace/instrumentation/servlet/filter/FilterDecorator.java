package datadog.trace.instrumentation.servlet.filter;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class FilterDecorator extends BaseDecorator {
  public static final CharSequence JAVA_WEB_SERVLET_FILTER =
      UTF8BytesString.create("java-web-servlet-filter");
  public static final CharSequence SERVLET_FILTER = UTF8BytesString.create("servlet.filter");
  public static final FilterDecorator DECORATE = new FilterDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"servlet-filter"};
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return JAVA_WEB_SERVLET_FILTER;
  }
}
