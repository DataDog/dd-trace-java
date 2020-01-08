package datadog.trace.instrumentation.finagle;

import datadog.trace.agent.decorator.ServerDecorator;
import datadog.trace.api.DDSpanTypes;

public class FinagleServiceDecorator extends ServerDecorator {
  public static FinagleServiceDecorator DECORATE = new FinagleServiceDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"finagle"};
  }

  @Override
  protected String spanType() {
    return DDSpanTypes.HTTP_SERVER;
  }

  @Override
  protected String component() {
    return "finagle";
  }
}
