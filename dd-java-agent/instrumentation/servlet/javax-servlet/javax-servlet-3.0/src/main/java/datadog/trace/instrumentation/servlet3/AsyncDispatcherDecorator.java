package datadog.trace.instrumentation.servlet3;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class AsyncDispatcherDecorator extends BaseDecorator {
  public static final AsyncDispatcherDecorator DECORATE = new AsyncDispatcherDecorator();
  public static final CharSequence JAVA_WEB_SERVLET_DISPATCHER =
      UTF8BytesString.create("java-web-servlet-async-dispatcher");

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"servlet", "servlet-3"};
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return JAVA_WEB_SERVLET_DISPATCHER;
  }
}
