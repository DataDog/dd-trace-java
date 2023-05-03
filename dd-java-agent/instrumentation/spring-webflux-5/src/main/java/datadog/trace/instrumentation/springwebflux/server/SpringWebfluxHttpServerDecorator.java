package datadog.trace.instrumentation.springwebflux.server;

import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ServerDecorator;

public class SpringWebfluxHttpServerDecorator extends ServerDecorator {
  public static final CharSequence DISPATCHER_HANDLE_HANDLER =
      UTF8BytesString.create("DispatcherHandler.handle");
  public static final CharSequence SPRING_WEBFLUX_CONTROLLER =
      UTF8BytesString.create("spring-webflux-controller");
  public static final SpringWebfluxHttpServerDecorator DECORATE =
      new SpringWebfluxHttpServerDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"spring-webflux"};
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.HTTP_SERVER;
  }

  @Override
  protected CharSequence component() {
    return SPRING_WEBFLUX_CONTROLLER;
  }
}
