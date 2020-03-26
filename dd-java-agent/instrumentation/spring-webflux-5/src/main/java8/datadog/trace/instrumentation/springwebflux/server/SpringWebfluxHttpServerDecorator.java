package datadog.trace.instrumentation.springwebflux.server;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.decorator.ServerDecorator;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SpringWebfluxHttpServerDecorator extends ServerDecorator {
  public static final SpringWebfluxHttpServerDecorator DECORATE =
      new SpringWebfluxHttpServerDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"spring-webflux"};
  }

  @Override
  protected String spanType() {
    return DDSpanTypes.HTTP_SERVER;
  }

  @Override
  protected String component() {
    return "spring-webflux-controller";
  }
}
